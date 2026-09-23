from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
import wave
from dataclasses import dataclass
from typing import Any, Callable

from . import __version__


RUNTIME_VERSION = "1.9.4"
RUNTIME_COMMIT = "927cfce34f31707e17f2bff35c349632fb9e2c3a"
CLI_SOURCE_URL = (
    "https://github.com/ggml-org/whisper.cpp/blob/"
    + RUNTIME_COMMIT
    + "/examples/cli/cli.cpp"
)
LANGUAGES = {"ko": "ko", "en": "en", "ja": "ja", "zh-CN": "zh", "zh": "zh"}
MAX_WAV_BYTES = 8 * 1024 * 1024
MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_AUDIO_SECONDS = 120
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class WorkerError(RuntimeError):
    """A failed invocation must never produce a successful transcript."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class RuntimeSpec:
    executable: Path
    sha256: str
    source_commit: str


@dataclass(frozen=True)
class ModelSpec:
    path: Path
    sha256: str


def _local_absolute_path(path: Path, kind: str, *, file: bool = True) -> Path:
    path = Path(path)
    # Remote SMB shares are outside this local worker's execution boundary.
    if not path.is_absolute() or str(path).startswith(("\\\\", "//")):
        raise WorkerError("INVALID_PATH", f"{kind} must use an absolute local path")
    try:
        resolved = path.resolve(strict=True)
    except OSError as exc:
        raise WorkerError("MISSING_ASSET", f"{kind} does not exist") from exc
    if str(resolved).startswith(("\\\\", "//")):
        raise WorkerError("INVALID_PATH", f"{kind} resolves to a network path")
    if file and (not resolved.is_file() or resolved.stat().st_size == 0):
        raise WorkerError("INVALID_ASSET", f"{kind} must be a non-empty regular file")
    if not file and not resolved.is_dir():
        raise WorkerError("INVALID_PATH", f"{kind} must be a directory")
    return resolved


def _check_hash(value: str, kind: str) -> None:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise WorkerError("INVALID_HASH", f"{kind} SHA-256 must be 64 lowercase hex characters")


def _guard(deadline: float, cancel: threading.Event | None) -> None:
    if cancel is not None and cancel.is_set():
        raise WorkerError("CANCELLED", "Transcription was cancelled")
    if time.perf_counter() >= deadline:
        raise WorkerError("TIMEOUT", "Transcription exceeded its total deadline")


def _hash_file(path: Path, check: Callable[[], None]) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            check()
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _verify_file(path: Path, expected: str, kind: str, check: Callable[[], None]) -> None:
    _check_hash(expected, kind)
    if _hash_file(path, check) != expected:
        raise WorkerError("HASH_MISMATCH", f"{kind} SHA-256 does not match the approved value")


def _snapshot_wav(source: Path, destination: Path, check: Callable[[], None]) -> str:
    digest = hashlib.sha256()
    size = 0
    with source.open("rb") as input_stream, destination.open("xb") as output_stream:
        while True:
            check()
            chunk = input_stream.read(64 * 1024)
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_WAV_BYTES:
                raise WorkerError("INVALID_AUDIO", "WAV exceeds the bounded input size")
            digest.update(chunk)
            output_stream.write(chunk)
    return digest.hexdigest()


def validate_wav(path: Path) -> float:
    """Validate actual frame bytes, not just the advertised WAV frame count."""
    try:
        with wave.open(str(path), "rb") as audio:
            if (
                audio.getnchannels() != 1
                or audio.getsampwidth() != 2
                or audio.getframerate() != 16000
                or audio.getcomptype() != "NONE"
            ):
                raise WorkerError("INVALID_AUDIO", "Expected uncompressed PCM16 mono 16 kHz WAV")
            frame_count = audio.getnframes()
            if not 0 < frame_count <= 16000 * MAX_AUDIO_SECONDS:
                raise WorkerError("INVALID_AUDIO", "Audio must contain 0 < duration <= 120 seconds")
            pcm = audio.readframes(frame_count)
            if len(pcm) != frame_count * 2:
                raise WorkerError("INVALID_AUDIO", "WAV frame data is truncated")
            return frame_count / 16.0
    except (wave.Error, EOFError, OSError) as exc:
        raise WorkerError("INVALID_AUDIO", "Cannot read a valid PCM WAV") from exc


def _stop(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=2.0)


def _run_process(
    command: list[str],
    directory: Path,
    deadline: float,
    cancel: threading.Event | None,
    *,
    stdout_path: Path | None = None,
    watched_output: Path | None = None,
    watched_limit: int = MAX_JSON_BYTES,
) -> float:
    _guard(deadline, cancel)
    started = time.perf_counter()
    # Clear inherited backend overrides; an arbitrary GGML backend search path
    # or WHISPER_ARG_DEVICE must not silently alter a pinned invocation.
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.upper().startswith(("GGML_", "WHISPER_"))
    }
    process = None
    output = stdout_path.open("xb") if stdout_path is not None else None
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=output if output is not None else subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            cwd=str(directory),
            env=environment,
            shell=False,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        while True:
            _guard(deadline, cancel)
            if watched_output is not None and watched_output.exists():
                if watched_output.is_symlink() or watched_output.resolve().parent != directory:
                    raise WorkerError("UNSAFE_OUTPUT", "Runtime output escaped its private directory")
                if watched_output.stat().st_size > watched_limit:
                    raise WorkerError("OUTPUT_TOO_LARGE", "Runtime output exceeded its size limit")
            try:
                code = process.wait(timeout=min(0.05, max(0.001, deadline - time.perf_counter())))
                break
            except subprocess.TimeoutExpired:
                continue
        _guard(deadline, cancel)
        if code != 0:
            raise WorkerError("RUNTIME_FAILED", f"whisper-cli exited with code {code}")
        return (time.perf_counter() - started) * 1000
    except OSError as exc:
        raise WorkerError("RUNTIME_START_FAILED", "Cannot start or communicate with whisper-cli") from exc
    finally:
        try:
            if process is not None:
                _stop(process)
        finally:
            if output is not None:
                output.close()


def _read_contained(path: Path, directory: Path, limit: int) -> bytes:
    if path.is_symlink() or not path.is_file() or path.resolve().parent != directory:
        raise WorkerError("MISSING_OUTPUT", "Runtime did not create a contained output file")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise WorkerError("OUTPUT_TOO_LARGE", "Runtime output exceeded its size limit")
    return data


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError(f"Non-finite JSON value: {value}")


def parse_transcript(
    payload: bytes,
    *,
    model_path: Path,
    language: str,
    duration_ms: float,
) -> tuple[str, list[dict[str, Any]]]:
    """Read the pinned CLI's JSON file; stdout is never a transcript fallback."""
    try:
        document = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
        if not isinstance(document, dict):
            raise ValueError("Expected object")
        params = document["params"]
        if (
            not isinstance(params, dict)
            or params.get("translate") is not False
            or params.get("language") != language
            or params.get("model") != str(model_path)
        ):
            raise ValueError("Runtime parameters do not match the request")
        if document["result"]["language"] != language:
            raise ValueError("Runtime language does not match the request")
        if document["model"]["multilingual"] is not True:
            raise ValueError("A multilingual Whisper model is required")
        raw_segments = document["transcription"]
        if not isinstance(raw_segments, list) or len(raw_segments) > 12000:
            raise ValueError("Invalid segments")
        segments: list[dict[str, Any]] = []
        previous_start = -1
        for item in raw_segments:
            text = item["text"]
            start, end = item["offsets"]["from"], item["offsets"]["to"]
            if (
                not isinstance(text, str)
                or type(start) is not int
                or type(end) is not int
                or not 0 <= start <= end <= duration_ms + 1000
                or start < previous_start
            ):
                raise ValueError("Invalid segment contents or offsets")
            previous_start = start
            segments.append({"startMs": start, "endMs": end, "text": text})
        transcript = "".join(segment["text"] for segment in segments).strip()
        return transcript, segments
    except (ValueError, KeyError, TypeError, UnicodeError, RecursionError) as exc:
        raise WorkerError("INVALID_OUTPUT", "Runtime JSON does not match the pinned transcription contract") from exc


def transcribe(
    runtime: RuntimeSpec,
    model: ModelSpec,
    source_wav: Path,
    workspace_temp: Path,
    *,
    language: str,
    backend: str = "cpu",
    threads: int = 4,
    timeout_seconds: float = 120.0,
    cancel: threading.Event | None = None,
    beam_size: int = 5,
    best_of: int = 5,
    audio_context: int = 0,
) -> dict[str, Any]:
    started = time.perf_counter()
    if type(beam_size) is not int or type(best_of) is not int or not 1 <= beam_size <= 5 or not 1 <= best_of <= 5:
        raise WorkerError("INVALID_REQUEST", "Decoding candidates must be between 1 and 5")
    if type(audio_context) is not int or audio_context not in (0,128,192,256,320,384,448,512,768,1024,1500):
        raise WorkerError("INVALID_REQUEST", "Unsupported audio context size")
    if (
        isinstance(timeout_seconds, bool)
        or not isinstance(timeout_seconds, (int, float))
        or not math.isfinite(timeout_seconds)
        or not 0 < timeout_seconds <= 3600
    ):
        raise WorkerError("INVALID_REQUEST", "timeout_seconds must be finite and between 0 and 3600")
    deadline = started + timeout_seconds
    check = lambda: _guard(deadline, cancel)
    check()
    if runtime.source_commit != RUNTIME_COMMIT:
        raise WorkerError("UNSUPPORTED_RUNTIME", "Only the reviewed whisper.cpp v1.9.4 commit is supported")
    if backend != "cpu":
        raise WorkerError(
            "UNSUPPORTED_BACKEND",
            "Only CPU is enabled; Vulkan device mapping and execution must pass calibration first",
        )
    if language not in LANGUAGES:
        raise WorkerError("UNSUPPORTED_LANGUAGE", "Supported languages are ko, en, ja, zh-CN (or zh)")
    if type(threads) is not int or not 1 <= threads <= 64:
        raise WorkerError("INVALID_REQUEST", "threads must be an integer between 1 and 64")
    executable = _local_absolute_path(runtime.executable, "Runtime executable")
    model_path = _local_absolute_path(model.path, "Model")
    source = _local_absolute_path(source_wav, "Source WAV")
    temp_root = _local_absolute_path(workspace_temp, "Workspace temp", file=False)
    if os.name == "nt" and executable.suffix.lower() != ".exe":
        raise WorkerError("INVALID_ASSET", "Windows runtime must be an explicit .exe, not a shell script")
    _verify_file(executable, runtime.sha256, "Runtime executable", check)
    _verify_file(model_path, model.sha256, "Model", check)
    whisper_language = LANGUAGES[language]

    # Each invocation owns only this newly created directory. It never deletes
    # the caller's temp root, input, model, or another invocation's files.
    with tempfile.TemporaryDirectory(prefix="mcasttalk-asr-", dir=temp_root) as temporary:
        directory = Path(temporary).resolve()
        wav_copy = directory / "input.wav"
        source_hash = _snapshot_wav(source, wav_copy, check)
        duration_ms = validate_wav(wav_copy)
        if audio_context and duration_ms > audio_context*20:
            raise WorkerError("INVALID_AUDIO", "Audio exceeds the explicitly configured context")
        version_path = directory / "version.txt"
        _run_process(
            [str(executable), "--version"],
            directory,
            deadline,
            cancel,
            stdout_path=version_path,
            watched_output=version_path,
            watched_limit=4096,
        )
        version = _read_contained(version_path, directory, 4096).decode("utf-8", errors="replace").strip()
        if version != f"whisper.cpp version: {RUNTIME_VERSION}":
            raise WorkerError("UNSUPPORTED_RUNTIME", "Executable version does not match the reviewed CLI")
        output_stem = directory / "result"
        output_path = directory / "result.json"
        settings = {
            "backend": "cpu",
            "threads": threads,
            "language": whisper_language,
            "translate": False,
            "beamSize": beam_size,
            "bestOf": best_of,
            "audioContext": audio_context,
            "temperature": 0.0,
            "temperatureIncrement": 0.2,
            "flashAttention": False,
        }
        command = [
            str(executable), "-m", str(model_path), "-f", str(wav_copy),
            "-l", whisper_language, "-t", str(threads), "-ng", "-nfa",
            "-bs", str(beam_size), "-bo", str(best_of), "-tp", "0", "-tpi", "0.2",
            "-oj", "-of", str(output_stem),
        ]
        if audio_context: command.extend(['-ac',str(audio_context)])
        process_ms = _run_process(
            command, directory, deadline, cancel, watched_output=output_path,
        )
        raw_output = _read_contained(output_path, directory, MAX_JSON_BYTES)
        text, segments = parse_transcript(
            raw_output, model_path=model_path, language=whisper_language, duration_ms=duration_ms,
        )
        check()
        settings_hash = hashlib.sha256(
            json.dumps(settings, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        return {
            "schemaVersion": 1,
            "status": "transcribed" if text else "no_speech",
            "text": text,
            "language": "zh-CN" if whisper_language == "zh" else whisper_language,
            "segments": segments,
            "evidence": {
                "adapterVersion": __version__,
                "runtime": "whisper.cpp",
                "runtimeVersion": RUNTIME_VERSION,
                "runtimeSourceCommit": RUNTIME_COMMIT,
                "runtimeSourceUrl": CLI_SOURCE_URL,
                "runtimeBinarySha256": runtime.sha256,
                "runtimeProvenance": "caller-declared-commit; executable-hash-and-version-verified",
                "modelSha256": model.sha256,
                "sourceWavSha256": source_hash,
                "outputJsonSha256": hashlib.sha256(raw_output).hexdigest(),
                "settingsSha256": settings_hash,
                "settings": settings,
                "inputDurationMs": duration_ms,
                "processLaunchToExitMs": process_ms,
                "totalLatencyMs": (time.perf_counter() - started) * 1000,
                "processRealTimeFactor": process_ms / duration_ms,
                "coldStartPerRequest": True,
                "includesModelLoad": True,
                "qualityEvaluated": False,
            },
        }

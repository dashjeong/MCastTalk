"""Contract tests using explicit fake subprocesses; these are NOT inference tests."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import wave

from mcasttalk_worker import whisper_cli as worker


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_wav(path: Path, *, channels: int = 1, rate: int = 16000, width: int = 2) -> None:
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(channels)
        stream.setsampwidth(width)
        stream.setframerate(rate)
        stream.writeframes(b"\x01\x00" * 16000 * channels)


def transcript_fixture(model: Path, language: str = "ko", text: str = " 안녕하세요.") -> dict:
    # Original, synthetic test data with the reviewed upstream JSON shape.
    return {
        "model": {"multilingual": True},
        "params": {"model": str(model), "language": language, "translate": False},
        "result": {"language": language},
        "transcription": [
            {"offsets": {"from": 0, "to": 1000}, "text": text},
        ],
    }


class FakeProcess:
    """No executable is launched. Only a CLI-shaped JSON fixture is emitted."""

    def __init__(self, command, *, kwargs, result, code=0, version=None):
        self.command = command
        self.returncode = None
        self.code = code
        self.terminated = False
        self.killed = False
        if command[-1] == "--version":
            kwargs["stdout"].write(version or b"whisper.cpp version: 1.9.4\n")
        elif result is not None:
            output = Path(command[command.index("-of") + 1] + ".json")
            output.write_bytes(result)

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        self.returncode = self.code
        return self.code

    def terminate(self):
        self.terminated = True
        self.returncode = -15

    def kill(self):
        self.killed = True
        self.returncode = -9


class WhisperAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.executable = self.root / "whisper-cli.exe"
        self.executable.write_bytes(b"NOT AN EXECUTABLE: unit-test fixture only")
        self.model = self.root / "model.bin"
        self.model.write_bytes(b"NOT MODEL WEIGHTS: unit-test fixture only")
        self.wav = self.root / "speech.wav"
        write_wav(self.wav)
        self.scratch = self.root / "worker-temp"
        self.scratch.mkdir()
        self.runtime_spec = worker.RuntimeSpec(
            self.executable, digest(self.executable), worker.RUNTIME_COMMIT,
        )
        self.model_spec = worker.ModelSpec(self.model, digest(self.model))
        self.processes = []
        self.invocations = []

    def tearDown(self):
        self.temp.cleanup()

    def fake_factory(self, *, result=None, code=0, version=None, no_output=False):
        if result is None and not no_output:
            result = json.dumps(transcript_fixture(self.model), ensure_ascii=False).encode("utf-8")

        def factory(command, **kwargs):
            self.invocations.append((command, kwargs))
            process = FakeProcess(
                command, kwargs=kwargs, result=result,
                code=code if command[-1] != "--version" else 0, version=version,
            )
            self.processes.append(process)
            return process

        return factory

    def transcribe(self, **kwargs):
        language = kwargs.pop("language", "ko")
        return worker.transcribe(
            self.runtime_spec, self.model_spec, self.wav, self.scratch,
            language=language, **kwargs,
        )

    def assert_worker_error(self, code, callable):
        with self.assertRaises(worker.WorkerError) as raised:
            callable()
        self.assertEqual(raised.exception.code, code)

    def test_parses_real_json_shape_and_records_exact_asset_evidence(self):
        with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory()):
            result = self.transcribe()
        self.assertEqual(result["text"], "안녕하세요.")
        self.assertEqual(result["status"], "transcribed")
        self.assertEqual(result["segments"][0]["endMs"], 1000)
        evidence = result["evidence"]
        self.assertEqual(evidence["sourceWavSha256"], digest(self.wav))
        self.assertEqual(evidence["modelSha256"], digest(self.model))
        self.assertEqual(evidence["runtimeBinarySha256"], digest(self.executable))
        self.assertEqual(evidence["runtimeSourceCommit"], worker.RUNTIME_COMMIT)
        self.assertEqual(evidence["inputDurationMs"], 1000)
        self.assertGreaterEqual(evidence["totalLatencyMs"], evidence["processLaunchToExitMs"])
        self.assertTrue(evidence["includesModelLoad"])
        self.assertFalse(evidence["qualityEvaluated"])
        command, options = self.invocations[-1]
        self.assertIn("-ng", command)
        self.assertIn("-nfa", command)
        self.assertNotIn("-tr", command)
        self.assertFalse(options["shell"])
        self.assertEqual(list(self.scratch.iterdir()), [])
        self.assertTrue(self.wav.exists())

    def test_language_mapping_uses_ja_not_jp_and_chinese_model_code(self):
        for product, model_code in worker.LANGUAGES.items():
            with self.subTest(language=product):
                result = json.dumps(transcript_fixture(self.model, model_code)).encode()
                with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(result=result)):
                    response = self.transcribe(language=product)
                command = self.invocations[-1][0]
                self.assertEqual(command[command.index("-l") + 1], model_code)
                self.assertEqual(response["language"], "zh-CN" if model_code == "zh" else model_code)
        self.assert_worker_error("UNSUPPORTED_LANGUAGE", lambda: self.transcribe(language="jp"))

    def test_unknown_revision_is_rejected_before_launch(self):
        self.runtime_spec = worker.RuntimeSpec(self.executable, digest(self.executable), "main")
        with patch.object(worker.subprocess, "Popen") as popen:
            self.assert_worker_error("UNSUPPORTED_RUNTIME", self.transcribe)
            popen.assert_not_called()

    def test_short_context_is_explicit_in_command_and_evidence(self):
        with patch.object(worker.subprocess,"Popen",side_effect=self.fake_factory()):
            result=self.transcribe(audio_context=512,beam_size=1,best_of=1)
        command=self.invocations[-1][0]
        self.assertEqual(command[command.index('-ac')+1],'512')
        self.assertEqual(result['evidence']['settings']['audioContext'],512)

    def test_invalid_context_never_launches(self):
        with patch.object(worker.subprocess,"Popen") as popen:
            for value in [True,-1,257,1501,'512']:
                self.assert_worker_error('INVALID_REQUEST',lambda:self.transcribe(audio_context=value))
            popen.assert_not_called()

    def test_adaptive_context_values_are_sent_without_cropping(self):
        for value in [128,192,256,320,384,448,512]:
            with self.subTest(context=value),patch.object(worker.subprocess,'Popen',side_effect=self.fake_factory()):
                result=self.transcribe(audio_context=value)
                self.assertEqual(result['evidence']['settings']['audioContext'],value)

    def test_runtime_and_model_hash_mismatch_never_launch(self):
        for target in (self.executable, self.model):
            original = target.read_bytes()
            target.write_bytes(original + b"tampered")
            with patch.object(worker.subprocess, "Popen") as popen:
                self.assert_worker_error("HASH_MISMATCH", self.transcribe)
                popen.assert_not_called()
            target.write_bytes(original)

    def test_missing_asset_and_relative_paths_are_rejected(self):
        self.runtime_spec = worker.RuntimeSpec(self.root / "missing.exe", "0" * 64, worker.RUNTIME_COMMIT)
        self.assert_worker_error("MISSING_ASSET", self.transcribe)
        self.runtime_spec = worker.RuntimeSpec(Path("whisper-cli.exe"), "0" * 64, worker.RUNTIME_COMMIT)
        self.assert_worker_error("INVALID_PATH", self.transcribe)

    def test_unverified_gpu_is_not_silently_routed_to_cpu(self):
        with patch.object(worker.subprocess, "Popen") as popen:
            self.assert_worker_error("UNSUPPORTED_BACKEND", lambda: self.transcribe(backend="vulkan"))
            popen.assert_not_called()

    def test_runtime_version_must_match_even_with_matching_binary_hash(self):
        with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(version=b"whisper.cpp version: 1.8.3")):
            self.assert_worker_error("UNSUPPORTED_RUNTIME", self.transcribe)
        self.assertEqual(len(self.invocations), 1)
        self.assertEqual(list(self.scratch.iterdir()), [])

    def test_nonzero_exit_does_not_accept_even_valid_json(self):
        with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(code=3)):
            self.assert_worker_error("RUNTIME_FAILED", self.transcribe)
        self.assertEqual(list(self.scratch.iterdir()), [])

    def test_zero_exit_without_json_is_failure_not_empty_success(self):
        with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(no_output=True)):
            self.assert_worker_error("MISSING_OUTPUT", self.transcribe)

    def test_malformed_json_is_not_replaced_with_stdout_or_fixture(self):
        with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(result=b"not JSON")):
            self.assert_worker_error("INVALID_OUTPUT", self.transcribe)

    def test_valid_empty_transcription_is_explicit_no_speech(self):
        fixture = transcript_fixture(self.model)
        fixture["transcription"] = []
        with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(result=json.dumps(fixture).encode())):
            result = self.transcribe()
        self.assertEqual(result["status"], "no_speech")
        self.assertEqual(result["text"], "")

    def test_wrong_runtime_parameters_and_invalid_offsets_are_rejected(self):
        for mutation in (
            lambda doc: doc["params"].update(translate=True),
            lambda doc: doc["params"].update(model="other.bin"),
            lambda doc: doc["result"].update(language="en"),
            lambda doc: doc["model"].update(multilingual=False),
            lambda doc: doc["transcription"][0]["offsets"].update(to=-1),
            lambda doc: doc["transcription"][0]["offsets"].update(to=True),
            lambda doc: doc["transcription"][0]["offsets"].update(to=9000),
        ):
            fixture = transcript_fixture(self.model)
            mutation(fixture)
            with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory(result=json.dumps(fixture).encode())):
                self.assert_worker_error("INVALID_OUTPUT", self.transcribe)

    def test_duplicate_json_keys_are_rejected(self):
        self.assert_worker_error(
            "INVALID_OUTPUT",
            lambda: worker.parse_transcript(
                b'{"transcription":[],"transcription":[]}',
                model_path=self.model, language="ko", duration_ms=1000,
            ),
        )

    def test_audio_format_and_truncation_are_rejected_before_launch(self):
        for settings in ({"channels": 2}, {"rate": 48000}, {"width": 1}):
            write_wav(self.wav, **settings)
            with patch.object(worker.subprocess, "Popen") as popen:
                self.assert_worker_error("INVALID_AUDIO", self.transcribe)
                popen.assert_not_called()
        write_wav(self.wav)
        self.wav.write_bytes(self.wav.read_bytes()[:-20])
        self.assert_worker_error("INVALID_AUDIO", self.transcribe)

    def test_output_size_is_bounded_and_cleanup_preserves_unrelated_data(self):
        sentinel = self.scratch / "keep.txt"
        sentinel.write_text("user data")
        with patch.object(worker, "MAX_JSON_BYTES", 32):
            with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory()):
                self.assert_worker_error("OUTPUT_TOO_LARGE", self.transcribe)
        self.assertEqual(sentinel.read_text(), "user data")
        self.assertEqual(list(self.scratch.iterdir()), [sentinel])

    def test_output_outside_invocation_directory_is_rejected(self):
        outside = self.root / "result.json"
        outside.write_bytes(b"{}")
        self.assert_worker_error(
            "MISSING_OUTPUT", lambda: worker._read_contained(outside, self.scratch, 100),
        )

    def test_cancel_before_launch_produces_no_transcript(self):
        cancel = threading.Event()
        cancel.set()
        with patch.object(worker.subprocess, "Popen") as popen:
            self.assert_worker_error("CANCELLED", lambda: self.transcribe(cancel=cancel))
            popen.assert_not_called()

    def test_running_timeout_and_cancel_terminate_child(self):
        for should_cancel in (False, True):
            cancel = threading.Event()
            process = FakeProcess(["fixture"], kwargs={}, result=None)

            def waiting(timeout=None):
                if process.returncode is not None:
                    return process.returncode
                if should_cancel:
                    cancel.set()
                else:
                    threading.Event().wait(timeout)
                raise subprocess.TimeoutExpired("fixture", timeout)

            process.wait = waiting
            with patch.object(worker.subprocess, "Popen", return_value=process):
                self.assert_worker_error(
                    "CANCELLED" if should_cancel else "TIMEOUT",
                    lambda: worker._run_process(
                        [str(self.executable)], self.scratch,
                        time.perf_counter() + 0.02, cancel,
                    ),
                )
            self.assertTrue(process.terminated)
            self.assertEqual(process.returncode, -15)

    def test_bad_deadlines_and_threads_are_rejected(self):
        for timeout in (0, -1, float("nan"), float("inf"), True):
            self.assert_worker_error("INVALID_REQUEST", lambda: self.transcribe(timeout_seconds=timeout))
        for threads in (0, 65, True, 1.5):
            self.assert_worker_error("INVALID_REQUEST", lambda: self.transcribe(threads=threads))

    def test_inherited_backend_overrides_are_removed(self):
        with patch.dict(worker.os.environ, {"GGML_BACKEND_PATH": "untrusted", "WHISPER_ARG_DEVICE": "999"}):
            with patch.object(worker.subprocess, "Popen", side_effect=self.fake_factory()):
                self.transcribe()
        environment = self.invocations[-1][1]["env"]
        self.assertNotIn("GGML_BACKEND_PATH", environment)
        self.assertNotIn("WHISPER_ARG_DEVICE", environment)


if __name__ == "__main__":
    unittest.main()

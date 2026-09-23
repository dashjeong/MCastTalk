from __future__ import annotations

import json
import os
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


DATA_ROOT_SCHEMA_VERSION = 1
DATA_DIRECTORIES = (
    "config",
    "db",
    "models/asr",
    "models/nmt",
    "models/tts",
    "models/vad",
    "models/llm",
    "voices",
    "dictionaries",
    "packages",
    "manifests",
    "licenses",
    "transcripts",
    "recordings",
    "cache/audio",
    "cache/translation",
    "certs",
    "logs",
    "diagnostics",
    "temp",
)


class DataRootError(RuntimeError):
    """Raised when the selected data root cannot be initialized safely."""


@dataclass(frozen=True)
class DataRootLayout:
    root: Path
    created_directories: tuple[Path, ...]
    marker_path: Path
    instance_id: str

    def as_dict(self) -> dict[str, object]:
        return {
            "root": str(self.root),
            "createdDirectories": [str(path) for path in self.created_directories],
            "markerPath": str(self.marker_path),
            "instanceId": self.instance_id,
            "schemaVersion": DATA_ROOT_SCHEMA_VERSION,
        }


def default_data_root(executable_dir: Path | None = None) -> Path:
    if executable_dir is not None:
        base = executable_dir
    elif getattr(sys, "frozen", False):
        base = Path(sys.executable).resolve().parent
    else:
        base = Path.cwd()
    return (base / "MCastTalkData").resolve()


def resolve_data_root(
    requested: str | os.PathLike[str] | None,
    executable_dir: Path | None = None,
) -> Path:
    if requested is None or str(requested).strip() == "":
        return default_data_root(executable_dir)
    return Path(requested).expanduser().resolve()


def _assert_writable_directory(path: Path) -> None:
    if path.exists() and not path.is_dir():
        raise DataRootError(f"Data root points to a file: {path}")
    try:
        path.mkdir(parents=True, exist_ok=True)
        probe = path / f".mcasttalk-write-probe-{uuid.uuid4().hex}"
        probe.write_bytes(b"ok")
        probe.unlink()
    except OSError as exc:
        raise DataRootError(f"Data root is not writable: {path}: {exc}") from exc


def _atomic_write_json(path: Path, value: dict[str, object]) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _load_or_create_marker(marker_path: Path, root: Path) -> dict[str, object]:
    if marker_path.exists():
        try:
            marker = json.loads(marker_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise DataRootError(f"Invalid data-root marker: {marker_path}: {exc}") from exc
        if marker.get("schemaVersion") != DATA_ROOT_SCHEMA_VERSION:
            raise DataRootError(
                f"Unsupported data-root schema: {marker.get('schemaVersion')}"
            )
        instance_id = marker.get("instanceId")
        if not isinstance(instance_id, str) or not instance_id:
            raise DataRootError(f"Missing instanceId in {marker_path}")
        return marker

    marker = {
        "schemaVersion": DATA_ROOT_SCHEMA_VERSION,
        "instanceId": str(uuid.uuid4()),
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
    }
    _atomic_write_json(marker_path, marker)
    return marker


def initialize_data_root(
    root: Path,
    directories: Iterable[str] = DATA_DIRECTORIES,
) -> DataRootLayout:
    resolved = root.expanduser().resolve()
    _assert_writable_directory(resolved)

    created: list[Path] = []
    for relative in directories:
        relative_path = Path(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise DataRootError(f"Unsafe data directory entry: {relative}")
        destination = resolved / relative_path
        if destination.exists() and not destination.is_dir():
            raise DataRootError(f"Expected a directory but found a file: {destination}")
        if not destination.exists():
            destination.mkdir(parents=True, exist_ok=True)
            created.append(destination)

    marker_path = resolved / "config" / "data-root.json"
    marker = _load_or_create_marker(marker_path, resolved)
    return DataRootLayout(
        root=resolved,
        created_directories=tuple(created),
        marker_path=marker_path,
        instance_id=str(marker["instanceId"]),
    )

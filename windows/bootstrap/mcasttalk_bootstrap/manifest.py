from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path


PACKAGE_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
LANGUAGE_TAG = re.compile(r"^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
SHA256 = re.compile(r"^[a-fA-F0-9]{64}$")
COMPONENTS = {"asr", "nmt", "tts", "vad", "llm"}
BACKENDS = {
    "cpu",
    "vulkan",
    "winml",
    "directml",
    "openvino",
    "cuda",
    "tensorrt",
    "hip",
    "qnn",
}
BLOCKED_LICENSE_MARKERS = (
    "CC-BY-NC",
    "CC BY-NC",
    "NONCOMMERCIAL",
    "NON-COMMERCIAL",
    "RESEARCH ONLY",
    "CPML",
)
REVIEW_LICENSE_MARKERS = ("GPL", "AGPL", "LGPL", "MPL")


@dataclass(frozen=True)
class VerificationFinding:
    severity: str
    code: str
    message: str

    def as_dict(self) -> dict[str, str]:
        return {
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
        }


@dataclass(frozen=True)
class ModelPackVerification:
    package_dir: Path
    package_id: str | None
    accepted: bool
    findings: tuple[VerificationFinding, ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "packageDir": str(self.package_dir),
            "packageId": self.package_id,
            "accepted": self.accepted,
            "findings": [finding.as_dict() for finding in self.findings],
        }


def _finding(severity: str, code: str, message: str) -> VerificationFinding:
    return VerificationFinding(severity=severity, code=code, message=message)


def _safe_pack_path(package_dir: Path, relative: object) -> tuple[Path | None, str | None]:
    if not isinstance(relative, str) or not relative:
        return None, "File path must be a non-empty string"
    relative_path = Path(relative)
    if relative_path.is_absolute() or ".." in relative_path.parts:
        return None, f"Unsafe model-pack path: {relative}"
    resolved = (package_dir / relative_path).resolve()
    try:
        resolved.relative_to(package_dir.resolve())
    except ValueError:
        return None, f"Model-pack path escapes package directory: {relative}"
    return resolved, None


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_model_pack(package_dir: Path) -> ModelPackVerification:
    root = package_dir.expanduser().resolve()
    findings: list[VerificationFinding] = []
    manifest_path = root / "model-pack.json"
    if not manifest_path.is_file():
        return ModelPackVerification(
            package_dir=root,
            package_id=None,
            accepted=False,
            findings=(
                _finding("error", "manifest.missing", f"Missing {manifest_path}"),
            ),
        )

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return ModelPackVerification(
            package_dir=root,
            package_id=None,
            accepted=False,
            findings=(
                _finding("error", "manifest.invalid_json", str(exc)),
            ),
        )

    if not isinstance(manifest, dict):
        return ModelPackVerification(
            package_dir=root,
            package_id=None,
            accepted=False,
            findings=(
                _finding("error", "manifest.invalid_type", "Manifest must be an object"),
            ),
        )

    package_id_value = manifest.get("packageId")
    package_id = package_id_value if isinstance(package_id_value, str) else None
    if package_id is None or PACKAGE_ID.fullmatch(package_id) is None:
        findings.append(_finding("error", "manifest.package_id", "Invalid packageId"))
    if manifest.get("schemaVersion") != 2:
        findings.append(_finding("error", "manifest.schema", "schemaVersion must be 2"))
    if manifest.get("component") not in COMPONENTS:
        findings.append(_finding("error", "manifest.component", "Invalid component"))
    if not isinstance(manifest.get("engine"), str) or not manifest["engine"].strip():
        findings.append(_finding("error", "manifest.engine", "engine is required"))
    supported_backends = manifest.get("supportedBackends")
    if (
        not isinstance(supported_backends, list)
        or not supported_backends
        or any(item not in BACKENDS for item in supported_backends)
        or len(set(supported_backends)) != len(supported_backends)
    ):
        findings.append(
            _finding(
                "error",
                "manifest.backends",
                "supportedBackends must contain unique approved backend identifiers",
            )
        )
    if not isinstance(manifest.get("version"), str) or not manifest["version"].strip():
        findings.append(_finding("error", "manifest.version", "version is required"))

    languages = manifest.get("supportedLanguages")
    if (
        not isinstance(languages, list)
        or not languages
        or any(
            not isinstance(item, str) or LANGUAGE_TAG.fullmatch(item) is None
            for item in languages
        )
    ):
        findings.append(
            _finding("error", "manifest.languages", "Invalid supportedLanguages")
        )

    license_value = manifest.get("licenseSpdx")
    if not isinstance(license_value, str) or not license_value.strip():
        findings.append(_finding("error", "license.missing", "licenseSpdx is required"))
    else:
        normalized = license_value.upper()
        if any(marker in normalized for marker in BLOCKED_LICENSE_MARKERS):
            findings.append(
                _finding(
                    "error",
                    "license.noncommercial",
                    f"Blocked product license: {license_value}",
                )
            )
        elif any(marker in normalized for marker in REVIEW_LICENSE_MARKERS):
            findings.append(
                _finding(
                    "review",
                    "license.copyleft_review",
                    f"Legal distribution review required: {license_value}",
                )
            )
    if manifest.get("distributionAllowed") is not True:
        findings.append(
            _finding(
                "error",
                "license.distribution",
                "distributionAllowed must be explicitly true",
            )
        )

    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        findings.append(_finding("error", "manifest.files", "files must not be empty"))
    else:
        seen: set[str] = set()
        for index, entry in enumerate(files):
            prefix = f"files[{index}]"
            if not isinstance(entry, dict):
                findings.append(
                    _finding("error", f"{prefix}.type", "File entry must be an object")
                )
                continue
            relative = entry.get("path")
            file_path, path_error = _safe_pack_path(root, relative)
            if path_error is not None:
                findings.append(_finding("error", f"{prefix}.path", path_error))
                continue
            assert file_path is not None
            canonical_relative = file_path.relative_to(root).as_posix().casefold()
            if canonical_relative in seen:
                findings.append(
                    _finding("error", f"{prefix}.duplicate", "Duplicate file path")
                )
                continue
            seen.add(canonical_relative)
            expected_hash = entry.get("sha256")
            expected_size = entry.get("sizeBytes")
            if not isinstance(expected_hash, str) or SHA256.fullmatch(expected_hash) is None:
                findings.append(
                    _finding("error", f"{prefix}.sha256", "Invalid SHA-256")
                )
            if (
                not isinstance(expected_size, int)
                or isinstance(expected_size, bool)
                or expected_size < 0
            ):
                findings.append(
                    _finding("error", f"{prefix}.size", "Invalid sizeBytes")
                )
            if not file_path.is_file():
                findings.append(
                    _finding("error", f"{prefix}.missing", f"Missing {relative}")
                )
                continue
            actual_size = file_path.stat().st_size
            if isinstance(expected_size, int) and actual_size != expected_size:
                findings.append(
                    _finding(
                        "error",
                        f"{prefix}.size_mismatch",
                        f"Expected {expected_size}, found {actual_size}",
                    )
                )
            if isinstance(expected_hash, str) and SHA256.fullmatch(expected_hash):
                actual_hash = _sha256(file_path)
                if actual_hash.casefold() != expected_hash.casefold():
                    findings.append(
                        _finding(
                            "error",
                            f"{prefix}.hash_mismatch",
                            f"SHA-256 mismatch for {relative}",
                        )
                    )

    accepted = not any(finding.severity in {"error", "review"} for finding in findings)
    if accepted:
        findings.append(_finding("info", "pack.verified", "Model pack verified"))
    return ModelPackVerification(
        package_dir=root,
        package_id=package_id,
        accepted=accepted,
        findings=tuple(findings),
    )

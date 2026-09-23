"""Verify all bundled offline files without network access."""
import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath


def verify(app):
    root = (app / "offline").resolve(strict=True)
    lines = (root / "manifest.properties").read_text(encoding="utf-8").splitlines()
    if not lines:
        raise ValueError("Empty offline manifest")
    seen = set()
    for line in lines:
        relative, separator, expected = line.partition("=")
        parts = relative.split("/")
        if (not separator or not relative or "\\" in relative or ":" in relative
                or PurePosixPath(relative).is_absolute()
                or any(p in ("", ".", "..") or p.endswith((" ", ".")) for p in parts)):
            raise ValueError("Invalid manifest path")
        if relative.casefold() in seen:
            raise ValueError("Duplicate manifest path: " + relative)
        seen.add(relative.casefold())
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError("Invalid SHA-256: " + relative)
        file = (root / relative).resolve(strict=True)
        if not file.is_relative_to(root):
            raise ValueError("Manifest path outside bundle")
        with file.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise ValueError("Payload hash mismatch: " + relative)
    actual_files = {p.relative_to(root).as_posix().casefold() for p in root.rglob("*")
                    if p.is_file() and p != root / "manifest.properties"}
    if actual_files != seen:
        raise ValueError("Offline bundle contains unlisted files: " + ", ".join(sorted(actual_files - seen)[:10]))
    legal = app / "legal"
    evidence = json.loads((legal / "manifest.json").read_text(encoding="utf-8"))
    viewer = (legal / "THIRD_PARTY_LICENSES.html").read_bytes()
    if hashlib.sha256(viewer).hexdigest() != evidence["viewerSha256"]:
        raise ValueError("License viewer SHA mismatch")
    return {"passed": True, "offlineFilesVerified": len(seen), "licenseViewerVerified": True,
            "networkIsolationTest": False}


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = verify(args.app)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"passed": False, "networkIsolationTest": False, "error": str(error)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Offline, deterministic repackaging and verification of the reviewed Moonshine patch."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "third_party/moonshine/0.1.5-mcasttalk1/provenance.json"
NATIVE_NAMES = {"libmoonshine.so", "libmoonshine-jni.so", "libonnxruntime.so"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def require_sha(data, expected, label):
    if sha256(data) != expected:
        raise ValueError(f"SHA-256 mismatch: {label}")


def checked_entries(archive):
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    if len(names) != len(set(names)):
        raise ValueError("duplicate archive entry")
    for entry in entries:
        path = PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
            raise ValueError("unsafe archive entry")
        if (entry.external_attr >> 16) & 0o170000 == 0o120000:
            raise ValueError("archive symlink")
    return entries


def repack(upstream_bytes, native_dir, manifest):
    require_sha(upstream_bytes, manifest["upstreamAar"]["sha256"], "upstream AAR")
    replacements = manifest["replacementEntries"]
    replacement_data = {}
    for item in replacements:
        data = (native_dir / item["fileName"]).read_bytes()
        require_sha(data, item["sha256"], item["fileName"])
        replacement_data[item["aarPath"]] = data
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(upstream_bytes)) as source:
        entries = checked_entries(source)
        for item in replacements:
            require_sha(source.read(item["aarPath"]), item["originalSha256"], item["aarPath"])
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as destination:
            for entry in sorted(entries, key=lambda value: value.filename):
                name = entry.filename
                if entry.is_dir():
                    continue
                if name.startswith("jni/") and not name.startswith("jni/arm64-v8a/"):
                    continue
                data = replacement_data.get(name)
                if data is None:
                    data = source.read(entry)
                metadata = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                metadata.compress_type = zipfile.ZIP_DEFLATED
                metadata.create_system = 3
                metadata.external_attr = 0o100644 << 16
                destination.writestr(metadata, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


def verify_aar(data, manifest):
    require_sha(data, manifest["packagedAar"]["sha256"], "patched AAR")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = checked_entries(archive)
        actual_native = {entry.filename for entry in entries if entry.filename.startswith("jni/") and not entry.is_dir()}
        expected = manifest["aarNativeEntries"]
        if actual_native != set(expected):
            raise ValueError("missing or unreviewed AAR native entry/ABI")
        for path, digest in expected.items():
            require_sha(archive.read(path), digest, path)


def verify_apk(path, manifest, notice_bytes):
    with zipfile.ZipFile(path) as archive:
        entries = checked_entries(archive)
        actual = {entry.filename for entry in entries if entry.filename.startswith("lib/") and PurePosixPath(entry.filename).name in NATIVE_NAMES}
        expected = manifest["apkNativeEntries"]
        if actual != set(expected):
            raise ValueError("missing or unreviewed APK native entry/ABI")
        for name, digest in expected.items():
            require_sha(archive.read(name), digest, name)
        if archive.read("assets/licenses/MOONSHINE-MCASTTALK-PATCH.txt") != notice_bytes:
            raise ValueError("missing or changed packaged patch notice")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package = commands.add_parser("repack", help="Verify pinned inputs and atomically reproduce the local AAR")
    package.add_argument("--upstream-aar", type=Path, required=True)
    package.add_argument("--native-dir", type=Path, required=True, help="Contains the two already-stripped ARM64 replacements")
    commands.add_parser("check-aar", help="Verify the committed AAR, patch and native payload hashes")
    apk = commands.add_parser("check-apk", help="Check actual APK native hashes and offline patch notice")
    apk.add_argument("apk", type=Path)
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text())
    patch = manifest["sourcePatch"]
    require_sha((ROOT / patch["path"]).read_bytes(), patch["sha256"], "source patch")
    aar = ROOT / manifest["packagedAar"]["path"]
    if args.command == "repack":
        data = repack(args.upstream_aar.read_bytes(), args.native_dir, manifest)
        verify_aar(data, manifest)
        aar.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=aar.parent, suffix=".tmp", delete=False) as temporary:
            temporary.write(data)
            temporary_path = Path(temporary.name)
        try:
            temporary_path.replace(aar)
        finally:
            temporary_path.unlink(missing_ok=True)
    verify_aar(aar.read_bytes(), manifest)
    if args.command == "check-apk":
        verify_apk(args.apk, manifest, (ROOT / "app/src/main/assets/licenses/MOONSHINE-MCASTTALK-PATCH.txt").read_bytes())
    print(json.dumps({"status": "PASS", "check": args.command, "aar_sha256": manifest["packagedAar"]["sha256"], "apk_sha256": sha256(args.apk.read_bytes()) if args.command == "check-apk" else None}))


if __name__ == "__main__":
    main()

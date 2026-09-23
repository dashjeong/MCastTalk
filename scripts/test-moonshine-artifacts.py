#!/usr/bin/env python3
"""Synthetic archive regressions; no Android tools, model files or network."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("moonshine_artifacts", Path(__file__).with_name("moonshine-artifacts.py"))
artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifacts)


def archive_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in entries:
            archive.writestr(name, data)
    return stream.getvalue()


class MoonshineArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.old = b"upstream-native"
        self.new = b"reviewed-native"
        self.name = "jni/arm64-v8a/libmoonshine.so"
        self.upstream = archive_bytes([(self.name, self.old), ("classes.jar", b"same Java bytes"), ("res/values/strings.xml", b"same resource bytes"), ("jni/x86_64/libmoonshine.so", b"unpatched other ABI")])
        (self.directory / "libmoonshine.so").write_bytes(self.new)
        self.manifest = {"upstreamAar": {"sha256": artifacts.sha256(self.upstream)}, "replacementEntries": [{"fileName": "libmoonshine.so", "aarPath": self.name, "originalSha256": artifacts.sha256(self.old), "sha256": artifacts.sha256(self.new)}]}

    def test_repack_is_repeatable_preserves_resources_and_removes_unpatched_abi(self):
        first = artifacts.repack(self.upstream, self.directory, self.manifest)
        self.assertEqual(first, artifacts.repack(self.upstream, self.directory, self.manifest))
        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertEqual(set(archive.namelist()), {self.name, "classes.jar", "res/values/strings.xml"})
            self.assertEqual(archive.read(self.name), self.new)
            self.assertEqual(archive.read("classes.jar"), b"same Java bytes")
            self.assertEqual(archive.read("res/values/strings.xml"), b"same resource bytes")

    def test_wrong_upstream_or_replacement_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "upstream AAR"):
            artifacts.repack(self.upstream + b"changed", self.directory, self.manifest)
        (self.directory / "libmoonshine.so").write_bytes(b"wrong build")
        with self.assertRaisesRegex(ValueError, "libmoonshine.so"):
            artifacts.repack(self.upstream, self.directory, self.manifest)

    def test_original_native_hash_is_not_skipped(self):
        self.manifest["replacementEntries"][0]["originalSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "jni/arm64"):
            artifacts.repack(self.upstream, self.directory, self.manifest)

    def test_apk_rejects_stale_runtime_extra_abi_and_missing_notice(self):
        path = self.directory / "synthetic.apk"
        native = "lib/arm64-v8a/libmoonshine.so"
        notice = "assets/licenses/MOONSHINE-MCASTTALK-PATCH.txt"
        manifest = {"apkNativeEntries": {native: artifacts.sha256(self.new)}}
        path.write_bytes(archive_bytes([(native, self.new), (notice, b"notice")]))
        artifacts.verify_apk(path, manifest, b"notice")
        for entries in [[(native, self.old), (notice, b"notice")], [(native, self.new), ("lib/x86_64/libmoonshine.so", self.old), (notice, b"notice")], [(native, self.new), (notice, b"wrong notice")]]:
            path.write_bytes(archive_bytes(entries))
            with self.assertRaises(ValueError):
                artifacts.verify_apk(path, manifest, b"notice")

    def test_duplicate_and_traversal_archive_members_rejected(self):
        for entries in [[("classes.jar", b"1"), ("classes.jar", b"2")], [("../classes.jar", b"1")]]:
            with zipfile.ZipFile(io.BytesIO(archive_bytes(entries))) as archive:
                with self.assertRaises(ValueError):
                    artifacts.checked_entries(archive)

    def test_public_archive_metadata_exception_keeps_other_guards(self):
        public_spec = importlib.util.spec_from_file_location("public_snapshot", Path(__file__).with_name("verify-public-snapshot.py"))
        public = importlib.util.module_from_spec(public_spec)
        public_spec.loader.exec_module(public)
        name = "META-INF/com/android/build/gradle/aar-metadata.properties"
        public.inspect(name, b"aarFormatVersion=1.0", depth=1)
        for forbidden in [name, "build/private.txt", "META-INF/com/android/build/private.txt"]:
            with self.assertRaises(ValueError):
                public.inspect(forbidden, b"content", depth=0)
        with self.assertRaises(ValueError):
            public.inspect(name, b"-----BEGIN " + b"PRIVATE" + b" KEY-----", depth=1)


if __name__ == "__main__":
    unittest.main()

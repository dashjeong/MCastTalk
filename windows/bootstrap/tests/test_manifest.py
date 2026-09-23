from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from mcasttalk_bootstrap.manifest import verify_model_pack


def manifest_for(
    file_name: str,
    payload: bytes,
    license_name: str = "Apache-2.0",
) -> dict[str, object]:
    return {
        "schemaVersion": 2,
        "packageId": "example.asr.test",
        "version": "1.0.0",
        "component": "asr",
        "engine": "test-engine",
        "supportedBackends": ["cpu", "vulkan", "directml"],
        "licenseSpdx": license_name,
        "distributionAllowed": True,
        "supportedLanguages": ["ko", "en", "ja", "zh-CN"],
        "files": [
            {
                "path": file_name,
                "sha256": hashlib.sha256(payload).hexdigest(),
                "sizeBytes": len(payload),
            }
        ],
    }


class ModelPackTests(unittest.TestCase):
    def _write_pack(
        self,
        root: Path,
        manifest: dict[str, object],
        payload: bytes = b"model",
    ) -> None:
        files = manifest.get("files")
        if isinstance(files, list) and files and isinstance(files[0], dict):
            relative = files[0].get("path")
            if isinstance(relative, str) and ".." not in Path(relative).parts:
                file_path = root / relative
                file_path.parent.mkdir(parents=True, exist_ok=True)
                file_path.write_bytes(payload)
        (root / "model-pack.json").write_text(
            json.dumps(manifest),
            encoding="utf-8",
        )

    def test_accepts_valid_pack(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"model"
            self._write_pack(root, manifest_for("weights/model.bin", payload), payload)
            result = verify_model_pack(root)
            self.assertTrue(result.accepted, result.findings)

    def test_blocks_noncommercial_license(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"model"
            self._write_pack(
                root,
                manifest_for("model.bin", payload, "CC-BY-NC-4.0"),
                payload,
            )
            result = verify_model_pack(root)
            self.assertFalse(result.accepted)
            self.assertIn(
                "license.noncommercial",
                {item.code for item in result.findings},
            )

    def test_requires_review_for_gpl(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"model"
            self._write_pack(
                root,
                manifest_for("model.bin", payload, "GPL-3.0-only"),
                payload,
            )
            result = verify_model_pack(root)
            self.assertFalse(result.accepted)
            self.assertIn(
                "license.copyleft_review",
                {item.code for item in result.findings},
            )

    def test_rejects_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            expected = manifest_for("model.bin", b"expected")
            self._write_pack(root, expected, b"actual")
            result = verify_model_pack(root)
            self.assertFalse(result.accepted)
            codes = {item.code for item in result.findings}
            self.assertTrue(
                "files[0].size_mismatch" in codes
                or "files[0].hash_mismatch" in codes
            )

    def test_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = manifest_for("../escape.bin", b"model")
            self._write_pack(root, manifest)
            result = verify_model_pack(root)
            self.assertFalse(result.accepted)
            self.assertIn(
                "files[0].path",
                {item.code for item in result.findings},
            )


if __name__ == "__main__":
    unittest.main()

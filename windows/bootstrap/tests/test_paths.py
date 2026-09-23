from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from mcasttalk_bootstrap.paths import (
    DATA_DIRECTORIES,
    DataRootError,
    default_data_root,
    initialize_data_root,
)


class DataRootTests(unittest.TestCase):
    def test_default_is_beside_executable_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            executable_dir = Path(temporary) / "portable"
            self.assertEqual(
                default_data_root(executable_dir),
                (executable_dir / "MCastTalkData").resolve(),
            )

    def test_initialize_is_idempotent_and_preserves_instance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "MCastTalkData"
            first = initialize_data_root(root)
            second = initialize_data_root(root)

            self.assertEqual(first.instance_id, second.instance_id)
            self.assertEqual(second.created_directories, ())
            for relative in DATA_DIRECTORIES:
                self.assertTrue((root / relative).is_dir(), relative)
            marker = json.loads(first.marker_path.read_text(encoding="utf-8"))
            self.assertEqual(marker["schemaVersion"], 1)
            self.assertEqual(marker["instanceId"], first.instance_id)

    def test_rejects_file_as_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "not-a-directory"
            root.write_text("x", encoding="utf-8")
            with self.assertRaises(DataRootError):
                initialize_data_root(root)

    def test_rejects_unsafe_directory_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(DataRootError):
                initialize_data_root(
                    Path(temporary) / "data",
                    directories=("../escape",),
                )


if __name__ == "__main__":
    unittest.main()

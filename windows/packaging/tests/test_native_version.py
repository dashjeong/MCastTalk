import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

PACKAGING = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGING))
spec = importlib.util.spec_from_file_location("stage_native_static", PACKAGING / "stage_native_static.py")
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class NativeVersionTest(unittest.TestCase):
    def test_reviewed_version_accepted(self):
        with patch.object(stage.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"whisper.cpp version: 1.9.4\r\n", b"")):
            self.assertEqual(stage.verify_whisper_version(Path("whisper-cli.exe")), "whisper.cpp version: 1.9.4")

    def test_dev_version_rejected_without_weakening_worker_check(self):
        with patch.object(stage.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"whisper.cpp version: 1.9.4-dev\n", b"")):
            with self.assertRaisesRegex(ValueError, "reviewed CLI"):
                stage.verify_whisper_version(Path("whisper-cli.exe"))

    def test_failed_executable_is_not_a_pass(self):
        with patch.object(stage.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "whisper")):
            with self.assertRaises(subprocess.CalledProcessError):
                stage.verify_whisper_version(Path("whisper-cli.exe"))


if __name__ == "__main__": unittest.main()

from __future__ import annotations

import unittest
from pathlib import Path


PACKAGING = Path(__file__).resolve().parents[1]


class PackagingScriptContractTest(unittest.TestCase):
    def test_package_is_offline_and_single_installer_or_image(self) -> None:
        script = (PACKAGING / "package.ps1").read_text(encoding="utf-8")
        self.assertIn('ValidateSet("exe", "app-image")', script)
        self.assertIn("--offline", script)
        self.assertIn('"--win-dir-chooser"', script)
        self.assertIn('"--main-class", "app.mcasttalk.windows.host.MainKt"', script)
        self.assertIn('"--java-options", "-Dfile.encoding=UTF-8"', script)
        self.assertNotIn("--java-options=-D", script)
        self.assertNotIn("Invoke-WebRequest", script)
        self.assertNotIn("winget", script.lower())
        self.assertNotIn("choco", script.lower())

    def test_detector_requires_wix_v3_pair_for_exe(self) -> None:
        script = (PACKAGING / "toolchain.ps1").read_text(encoding="utf-8")
        self.assertIn('"candle.exe"', script)
        self.assertIn('"light.exe"', script)
        self.assertIn("usableByJpackage", script)
        self.assertIn('$Type -eq "app-image"', script)

    def test_detector_prefers_repository_portable_jdk_as_a_pair(self) -> None:
        script = (PACKAGING / "toolchain.ps1").read_text(encoding="utf-8")
        self.assertIn('".tools\\jdk17"', script)
        self.assertIn('source = "repository-portable"', script)
        self.assertIn('Resolve-MCastTalkJdk', script)
        self.assertIn('javaHome', script)

    def test_docs_explain_first_run_and_no_download_policy(self) -> None:
        docs = (PACKAGING / "README.md").read_text(encoding="utf-8")
        self.assertIn("first-run workspace selection", docs)
        self.assertIn("does not download", docs)
        self.assertIn("MCastTalk-<version>.exe", docs)


if __name__ == "__main__":
    unittest.main()

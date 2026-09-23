from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import tempfile
import unittest
import zipfile
from html.parser import HTMLParser
from pathlib import Path


PACKAGING = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("build_notices", PACKAGING / "build_notices.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.hrefs = []
        self.tags = []
        self.sources = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.tags.append(tag)
        if "id" in attrs:
            self.ids.add(attrs["id"])
        if "href" in attrs:
            self.hrefs.append(attrs["href"])
        if "src" in attrs:
            self.sources.append(attrs["src"])


class OfflineNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app, self.repo = self.root / "app", self.root / "repo"
        self.repo.mkdir()
        (self.repo / "LICENSE").write_text("Apache License 2.0\n", encoding="utf-8")
        (self.repo / "NOTICE").write_text("MCastTalk copyright\n===\nThird-Party Software and Model Attributions\nANDROID ONLY", encoding="utf-8")
        paths = [
            "offline/assets/WHISPER-LICENSE.txt", "offline/assets/QWEN-LICENSE.txt",
            "offline/assets/LLAMA-LICENSE.txt", "offline/assets/llama-cpu/LICENSE-LLVM-OpenMP",
            "offline/assets/SUPERTONIC-MODEL-LICENSE.txt",
            "offline/assets/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/LICENSE",
            "offline/assets/vits-melo-tts-zh_en/LICENSE", "offline/python/LICENSE.txt",
            "offline/python/Lib/site-packages/numpy-2.2.6.dist-info/LICENSE.txt",
            "offline/python/Lib/site-packages/sherpa_onnx-1.13.8.dist-info/licenses/LICENSE",
            "runtime/legal/java.base/LICENSE",
        ]
        for name in paths:
            p = self.app / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("Original terms <script>not executable</script> & copyright\n" + name, encoding="utf-8")
        (self.app / "app").mkdir()
        with zipfile.ZipFile(self.app / "app/slf4j-api-2.0.18.jar", "w") as archive:
            archive.writestr("META-INF/LICENSE.txt", "MIT original copyright notice")
        with zipfile.ZipFile(self.app / "app/host.jar", "w"):
            pass

    def generate(self):
        self.result = module.build(self.app, self.repo)
        self.page = (self.app / "legal/THIRD_PARTY_LICENSES.html").read_text(encoding="utf-8")
        self.links = Links()
        self.links.feed(self.page)

    def test_viewer_has_no_external_resources(self):
        self.generate()
        self.assertEqual(self.links.sources, [])
        self.assertNotIn("script", self.links.tags)
        self.assertNotIn("link", self.links.tags)
        self.assertIn("default-src 'none'", self.page)

    def test_all_internal_links_resolve(self):
        self.generate()
        for href in self.links.hrefs:
            if href.startswith("#"):
                self.assertIn(href[1:], self.links.ids)

    def test_upstream_text_cannot_inject_html(self):
        self.generate()
        self.assertIn("&lt;script&gt;not executable&lt;/script&gt;", self.page)
        self.assertNotIn("script", self.links.tags)

    def test_external_links_are_explicit_https_references(self):
        self.generate()
        for href in self.links.hrefs:
            self.assertTrue(href.startswith(("#", "https://")))
        self.assertIn('rel="noopener noreferrer"', self.page)

    def test_sha256_covers_actual_viewer(self):
        self.generate()
        data = (self.app / "legal/THIRD_PARTY_LICENSES.html").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), self.result["viewerSha256"])

    def test_original_notice_is_preserved(self):
        self.generate()
        data = (self.app / "offline/assets/SUPERTONIC-MODEL-LICENSE.txt").read_bytes()
        self.assertIn(hashlib.sha256(data).hexdigest(), {d["sha256"] for d in self.result["documents"]})

    def test_model_and_example_code_licenses_are_distinct(self):
        self.generate()
        self.assertIn("OpenRAIL-M — 용도 제한 있음", self.page)
        self.assertIn("MIT (모델 가중치의 허가가 아님)", self.page)

    def test_missing_model_license_fails_without_partial_viewer(self):
        (self.app / "offline/assets/SUPERTONIC-MODEL-LICENSE.txt").unlink()
        with self.assertRaises(FileNotFoundError):
            module.build(self.app, self.repo)
        self.assertFalse((self.app / "legal").exists())

    def test_unknown_jar_requires_review(self):
        (self.app / "app/new-unreviewed-1.0.jar").touch()
        with self.assertRaisesRegex(ValueError, "Unreviewed JVM"):
            module.build(self.app, self.repo)
        self.assertFalse((self.app / "legal").exists())

    def test_missing_embedded_mit_fails(self):
        with zipfile.ZipFile(self.app / "app/slf4j-api-2.0.18.jar", "w"):
            pass
        with self.assertRaisesRegex(ValueError, "No local license"):
            module.build(self.app, self.repo)

    def test_existing_output_is_not_overwritten(self):
        self.generate()
        with self.assertRaisesRegex(ValueError, "Refusing to replace"):
            module.build(self.app, self.repo)

    def test_binary_notices_fail(self):
        (self.app / "offline/assets/QWEN-LICENSE.txt").write_bytes(b"binary\x00notice")
        with self.assertRaisesRegex(ValueError, "binary notice"):
            module.build(self.app, self.repo)

    def test_empty_notices_fail(self):
        (self.app / "offline/assets/QWEN-LICENSE.txt").write_text(" ")
        with self.assertRaisesRegex(ValueError, "Empty"):
            module.build(self.app, self.repo)

    def test_mobile_only_attributions_are_not_listed_as_pc_dependencies(self):
        self.generate()
        self.assertIn("MCastTalk copyright", self.page)
        self.assertNotIn("ANDROID ONLY", self.page)

    def test_no_missing_component_document_references(self):
        self.generate()
        known = {d["sha256"] for d in self.result["documents"]}
        for component in self.result["components"]:
            self.assertTrue(component["documents"])
            self.assertTrue(set(component["documents"]).issubset(known))

    def test_sources_do_not_expose_machine_paths(self):
        self.generate()
        evidence = json.dumps(self.result)
        self.assertNotIn(str(self.root), evidence)
        self.assertNotIn(str(self.root).replace("\\", "\\\\"), evidence)

    def test_empty_runtime_legal_fails(self):
        (self.app / "runtime/legal/java.base/LICENSE").unlink()
        with self.assertRaisesRegex(ValueError, "No local license"):
            module.build(self.app, self.repo)

    def test_every_known_family_is_classified(self):
        for name in ("kotlin-stdlib-2.jar", "kotlin-reflect-2.jar", "kotlinx-coroutines-core-1.jar",
                     "kotlinx-io-core-1.jar", "kotlinx-serialization-core-1.jar", "ktor-server-1.jar",
                     "netty-common-1.jar", "annotations-23.jar", "config-1.jar", "alpn-api-1.jar", "slf4j-api-2.jar"):
            self.assertTrue(module.classify_jar(name)[1])

    def test_static_runtime_notices_replace_unused_llvm(self):
        (self.app / "offline/assets/llama-cpu/LICENSE-LLVM-OpenMP").unlink()
        (self.app / "offline/assets/native-runtime-evidence.json").write_text('{}')
        folder = self.app / "offline/assets/native-licenses"
        folder.mkdir()
        for name in ("COPYING3.txt", "COPYING.RUNTIME.txt", "COPYING.MinGW-w64-runtime.txt"):
            (folder / name).write_text("Original runtime terms " + name)
        self.generate()
        self.assertIn("GCC Runtime Library Exception", self.page)
        self.assertIn("MinGW-w64", self.page)
        self.assertNotIn("LLVM OpenMP", self.page)

    def test_static_runtime_missing_exception_fails(self):
        (self.app / "offline/assets/native-runtime-evidence.json").write_text('{}')
        with self.assertRaises(FileNotFoundError): module.build(self.app, self.repo)

    def test_native_spdx_named_license_is_embedded_offline(self):
        path = self.app / "offline/assets/native-licenses/Vulkan-Headers/LICENSES/MIT.txt"
        path.parent.mkdir(parents=True)
        path.write_text("Complete Khronos MIT license test marker")
        self.generate()
        self.assertIn("Complete Khronos MIT license test marker", self.page)

    def test_installer_does_not_require_blanket_license_acceptance(self):
        script = (PACKAGING / "offline.iss").read_text(encoding="utf-8")
        self.assertIsNone(re.search(r"^LicenseFile=", script, re.M))
        self.assertNotIn("LicenseAccepted", script)
        self.assertNotIn("ACCEPTLICENSES", script)
        self.assertIn("InfoBeforeFile={#AppImage}\\legal\\INSTALLATION_OVERVIEW.txt", script)

    def test_license_viewer_is_available_before_and_after_install(self):
        script = (PACKAGING / "offline.iss").read_text(encoding="utf-8")
        self.assertIn("ExtractTemporaryFile('THIRD_PARTY_LICENSES.html')", script)
        self.assertIn('Filename: "{app}\\legal\\THIRD_PARTY_LICENSES.html"', script)
        self.assertIn("Flags: dontcopy", script)

    def test_overview_separates_install_from_model_conditions(self):
        text = (PACKAGING / "INSTALLATION_OVERVIEW.txt").read_text(encoding="utf-8")
        self.assertIn("일괄 수락하는 버튼이 아닙니다", text)
        self.assertIn("공식 사이트 링크는 참고용", text)
        self.assertIn("인터넷 없이", text)

    def test_packaging_does_not_download_notices_at_runtime(self):
        script = (PACKAGING / "stage-offline.cjs").read_text(encoding="utf-8")
        self.assertIn("build_notices.py", script)
        self.assertNotIn("fetch(", script)
        self.assertNotIn("https.get", script)


if __name__ == "__main__":
    unittest.main()

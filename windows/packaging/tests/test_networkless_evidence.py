"""Synthetic verifier tests; these are NOT proof of Windows network isolation."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("payload_verifier", HERE / "verify-offline-payload.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class PayloadEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "app"
        self.offline = self.app / "offline"
        self.legal = self.app / "legal"
        self.offline.mkdir(parents=True)
        self.legal.mkdir()
        (self.offline / "sample.txt").write_bytes(b"sample")
        self.digest = hashlib.sha256(b"sample").hexdigest()
        self.manifest = self.offline / "manifest.properties"
        self.line = "sample.txt=" + self.digest
        self.manifest.write_text(self.line + "\n", encoding="utf-8")
        (self.legal / "THIRD_PARTY_LICENSES.html").write_bytes(b"<html>license</html>")
        self.legal_manifest = self.legal / "manifest.json"
        self.legal_manifest.write_text(json.dumps({"viewerSha256": hashlib.sha256(b"<html>license</html>").hexdigest()}))

    def test_unlisted_generated_or_executable_file_is_rejected(self):
        (self.offline / "unlisted.pyc").write_bytes(b"generated cache")
        with self.assertRaisesRegex(ValueError, "unlisted files"):
            verifier.verify(self.app)

    def reject_manifest(self, text):
        self.manifest.write_text(text, encoding="utf-8")
        with self.assertRaises((ValueError, OSError)):
            verifier.verify(self.app)

    def test_valid_payload_does_not_claim_network_isolation(self):
        result = verifier.verify(self.app)
        self.assertTrue(result["passed"])
        self.assertEqual(result["offlineFilesVerified"], 1)
        self.assertFalse(result["networkIsolationTest"])

    def test_empty_manifest_fails(self):
        self.reject_manifest("")

    def test_blank_line_fails(self):
        self.reject_manifest(self.line + "\n\n")

    def test_missing_hash_separator_fails(self):
        self.reject_manifest("sample.txt")

    def test_invalid_hash_fails(self):
        self.reject_manifest("sample.txt=" + "g" * 64)

    def test_short_hash_fails(self):
        self.reject_manifest("sample.txt=" + self.digest[:-1])

    def test_duplicate_entry_fails(self):
        self.reject_manifest(self.line + "\n" + self.line)

    def test_windows_case_alias_fails(self):
        self.reject_manifest(self.line + "\nSAMPLE.TXT=" + self.digest)

    def test_parent_traversal_fails(self):
        self.reject_manifest("../sample.txt=" + self.digest)

    def test_absolute_path_fails(self):
        self.reject_manifest("/sample.txt=" + self.digest)

    def test_windows_drive_path_fails(self):
        self.reject_manifest("C:/sample.txt=" + self.digest)

    def test_windows_alternate_stream_fails(self):
        self.reject_manifest("sample.txt:stream=" + self.digest)

    def test_backslash_path_fails(self):
        self.reject_manifest("folder\\sample.txt=" + self.digest)

    def test_dot_component_fails(self):
        self.reject_manifest("./sample.txt=" + self.digest)

    def test_empty_component_fails(self):
        self.reject_manifest("folder//sample.txt=" + self.digest)

    def test_windows_trailing_dot_fails(self):
        self.reject_manifest("sample.txt.=" + self.digest)

    def test_windows_trailing_space_fails(self):
        self.reject_manifest("sample.txt =" + self.digest)

    def test_missing_payload_fails(self):
        self.reject_manifest("missing.txt=" + self.digest)

    def test_changed_payload_fails(self):
        (self.offline / "sample.txt").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "Payload hash mismatch"):
            verifier.verify(self.app)

    def test_changed_license_viewer_fails(self):
        (self.legal / "THIRD_PARTY_LICENSES.html").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "License viewer SHA mismatch"):
            verifier.verify(self.app)

    def test_cli_replaces_stale_success_with_failure(self):
        output = self.root / "result.json"
        output.write_text('{"passed": true}')
        self.manifest.write_text("")
        with contextlib.redirect_stdout(io.StringIO()):
            code = verifier.main(["--app", str(self.app), "--output", str(output)])
        self.assertEqual(code, 1)
        result = json.loads(output.read_text())
        self.assertFalse(result["passed"])
        self.assertFalse(result["networkIsolationTest"])

    def test_cli_success_creates_evidence(self):
        output = self.root / "evidence/result.json"
        with contextlib.redirect_stdout(io.StringIO()):
            code = verifier.main(["--app", str(self.app), "--output", str(output)])
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(output.read_text())["passed"])


@unittest.skipUnless(os.name == "nt" and shutil.which("powershell.exe"), "Windows PowerShell required")
class NetworkTopologyEvidenceTest(unittest.TestCase):
    def run_topology(self, routes="", adapters="", should_pass=False):
        # Mock only the read-only OS queries. Never change host networking or
        # make a real public connection from these synthetic negative tests.
        script_path = str(HERE / "networkless-assertions.ps1").replace("'", "''")
        script = f"""
$ErrorActionPreference = 'Stop'
. '{script_path}'
function Get-NetRoute {{ [CmdletBinding()]param(); {routes} }}
function Get-NetAdapter {{ [CmdletBinding()]param(); {adapters} }}
$ErrorActionPreference = 'Continue'
try {{ $result = Get-OfflineNetworkTopology; $result | ConvertTo-Json -Compress; exit 0 }}
catch {{ Write-Output $_.Exception.Message; exit 9 }}
"""
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
                                capture_output=True, text=True, timeout=25)
        self.assertEqual(result.returncode, 0 if should_pass else 9, result.stdout + result.stderr)
        if should_pass:
            self.assertEqual(json.loads(result.stdout), {"defaultRoutes": 0, "activeAdapters": 0})
        else:
            self.assertRegex(result.stdout, r"Sandbox is not fully network-disabled|simulated (route|adapter) query failure")

    def test_empty_topology_passes_topology_only(self):
        self.run_topology(should_pass=True)

    def test_ipv4_default_route_fails(self):
        self.run_topology(routes="[pscustomobject]@{DestinationPrefix='0.0.0.0/0'}")

    def test_ipv6_default_route_fails(self):
        self.run_topology(routes="[pscustomobject]@{DestinationPrefix='::/0'}")

    def test_active_adapter_fails_without_route(self):
        self.run_topology(adapters="[pscustomobject]@{Status='Up'}")

    def test_route_query_failure_cannot_pass(self):
        self.run_topology(routes="Write-Error 'simulated route query failure'")

    def test_adapter_query_failure_cannot_pass(self):
        self.run_topology(adapters="Write-Error 'simulated adapter query failure'")

    def test_loopback_route_and_disabled_adapter_pass_topology_only(self):
        self.run_topology(routes="[pscustomobject]@{DestinationPrefix='127.0.0.0/8'}",
                          adapters="[pscustomobject]@{Status='Disconnected'}", should_pass=True)


if __name__ == "__main__":
    unittest.main()

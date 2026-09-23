#!/usr/bin/env python3
"""Protocol regression tests only: no adb/device access and no production dependencies."""
import importlib.util
import contextlib
import io
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock

path = pathlib.Path(__file__).with_name("run-device-evidence.py")
spec = importlib.util.spec_from_file_location("device_evidence", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
parse = module.parse_instrumentation


def event(code, name="syntheticTest", current=1, total=1):
    return (f"INSTRUMENTATION_STATUS: class=example.SyntheticTest\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            f"INSTRUMENTATION_STATUS: current={current}\n"
            f"INSTRUMENTATION_STATUS: numtests={total}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def success(total=1):
    return f"INSTRUMENTATION_RESULT: stream=\n\nTime: 0.5\nOK ({total} tests)\nINSTRUMENTATION_CODE: -1\n"


class DeviceEvidenceParserTest(unittest.TestCase):
    def test_complete_run_requires_matching_protocol_and_junit_counts(self):
        output = "".join(event(code, f"test{n}", n, 2) for n in (1, 2) for code in (1, 0)) + success(2)
        result = parse(output)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(2, result["counters"]["passed"])
        self.assertEqual(["test1", "test2"], [test["test"] for test in result["tests"]])

    def test_shell_exit_zero_and_plausible_ok_alone_are_not_evidence(self):
        self.assertNotEqual("PASS", parse(success(), returncode=0)["verdict"])

    def test_interrupted_after_start_never_passes(self):
        result = parse(event(1), returncode=0)
        self.assertEqual("INCOMPLETE", result["verdict"])
        self.assertIn("Started tests are missing terminal statuses", result["issues"])

    def test_interruption_after_last_test_still_requires_runner_terminal(self):
        output = event(1) + event(0) + "OK (1 test)\n"
        self.assertEqual("INCOMPLETE", parse(output)["verdict"])

    def test_error_and_failure_are_counted_even_if_adb_exits_zero(self):
        output = event(1, "assertion", 1, 2) + event(-2, "assertion", 1, 2)
        output += event(1, "exception", 2, 2) + event(-1, "exception", 2, 2)
        output += "FAILURES!!!\nTests run: 2, Failures: 2\nINSTRUMENTATION_CODE: -1\n"
        result = parse(output)
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual(1, result["counters"]["failed"])
        self.assertEqual(1, result["counters"]["error"])
        self.assertEqual([], result["issues"])

    def test_ignored_or_assumption_skips_cannot_pass(self):
        for code in (-3, -4):
            with self.subTest(code=code):
                result = parse(event(1) + event(code) + success())
                self.assertEqual("SKIPPED", result["verdict"])
                self.assertEqual(1, result["counters"]["skipped"])

    def test_mismatched_declared_count_or_junit_summary_cannot_pass(self):
        for output in (event(1, total=2) + event(0, total=2) + success(),
                       event(1) + event(0) + success(2),
                       event(1) + event(0, total=2) + success()):
            with self.subTest(output=output):
                self.assertNotEqual("PASS", parse(output)["verdict"])

    def test_terminal_without_start_or_duplicate_terminal_cannot_pass(self):
        for output in (event(0) + success(), event(1) + event(0) + event(0) + success(2)):
            with self.subTest(output=output):
                self.assertNotEqual("PASS", parse(output)["verdict"])

    def test_timeout_or_nonzero_exit_overrides_otherwise_complete_output(self):
        output = event(1) + event(0) + success()
        self.assertEqual("INCOMPLETE", parse(output, timed_out=True)["verdict"])
        self.assertNotEqual("PASS", parse(output, returncode=1)["verdict"])

    def test_runner_crash_cannot_be_hidden_by_completed_tests(self):
        output = event(1) + event(0) + success() + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
        self.assertNotEqual("PASS", parse(output)["verdict"])


class DeviceEvidenceCorpusCommandTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.apk = self.root / "synthetic.apk"
        self.apk.write_bytes(b"synthetic artifact identity for mocked adb only")

    def arguments(self, serial="emulator-5554"):
        return ["--serial", serial, "--class", "example.SyntheticTest",
                "--apk", str(self.apk), "--test-apk", str(self.apk),
                "--output-dir", str(self.root / "evidence")]

    def test_both_manifest_options_reject_unsafe_paths_before_adb(self):
        for option in ("--corpus-manifest", "--native-corpus-manifest"):
            for value in ("../case.json", "/case.json", "./case.json", "a//case.json",
                          "a/../case.json", "", "a b.json", "x;echo.json", "x\\case.json"):
                with self.subTest(option=option, value=value), mock.patch.object(module.subprocess, "run") as run:
                    with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                        module.main(self.arguments() + [option, value])
                    self.assertEqual(2, error.exception.code)
                    run.assert_not_called()

    def test_manifest_options_are_mutually_exclusive_and_require_emulator(self):
        invalid = [self.arguments() + ["--corpus-manifest", "a.json", "--native-corpus-manifest", "b.json"]]
        for option in ("--corpus-manifest", "--native-corpus-manifest"):
            for serial in ("physical-test-device", "emulator-fake"):
                invalid.append(self.arguments(serial) + [option, "case.json"])
        for arguments in invalid:
            with self.subTest(arguments=arguments), mock.patch.object(module.subprocess, "run") as run:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    module.main(arguments)
                self.assertEqual(2, error.exception.code)
                run.assert_not_called()

    def test_each_manifest_generates_only_its_matching_argument_and_receipt_field(self):
        def fake_adb(command, **kwargs):
            if command[3:6] == ["shell", "am", "instrument"]:
                kwargs["stdout"].write((event(1) + event(0) + success()).encode())
                return subprocess.CompletedProcess(command, 0)
            if command[3:5] == ["shell", "getprop"]:
                output = b"synthetic-metadata\n"
            elif command[3:6] == ["shell", "pm", "path"]:
                output = b"package:/data/app/synthetic/base.apk\n"
            elif command[3:5] == ["shell", "sha256sum"]:
                output = (module.file_sha256(self.apk) + "  /data/app/synthetic/base.apk\n").encode()
            else:
                self.fail("Unexpected mocked adb command")
            return subprocess.CompletedProcess(command, 0, stdout=output, stderr=b"")

        for option, argument, field, other in (
                ("--corpus-manifest", "corpusManifest", "corpus_manifest", "native_corpus_manifest"),
                ("--native-corpus-manifest", "nativeCorpusManifest", "native_corpus_manifest", "corpus_manifest")):
            relative = "assigned/ko-12.json"
            with self.subTest(option=option), mock.patch.object(module.subprocess, "run", side_effect=fake_adb):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, module.main(self.arguments() + [option, relative]))
                reports = [json.loads(path.read_text()) for path in (self.root / "evidence").glob("*/evidence.json")]
                report = next(item for item in reports if item[field] == relative)
                self.assertIsNone(report[other])
                command = report["command_argv"]
                self.assertEqual(["-e", argument, relative, "-e", "dedicatedCorpusDevice", "true", module.RUNNER], command[-7:])
                self.assertNotIn("nativeCorpusManifest" if argument == "corpusManifest" else "corpusManifest", command)
                self.assertEqual("verified", report["test_apk_verification"])
                self.assertEqual("PASS", report["verdict"])

    def test_jni_manifest_rejects_unsafe_paths_before_adb(self):
        for value in ("../case.json", "/case.json", "./case.json", "a//case.json",
                      "a/../case.json", "", "a b.json", "x;echo.json", "x\\case.json"):
            with self.subTest(value=value), mock.patch.object(module.subprocess, "run") as run:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    module.main(self.arguments() + ["--jni-corpus-manifest", value])
                self.assertEqual(2, error.exception.code)
                run.assert_not_called()

    def test_jni_manifest_is_exclusive_with_both_modes_and_requires_emulator(self):
        invalid = [self.arguments() + [option, "a.json", "--jni-corpus-manifest", "b.json"]
                   for option in ("--corpus-manifest", "--native-corpus-manifest")]
        invalid += [self.arguments(serial) + ["--jni-corpus-manifest", "case.json"]
                    for serial in ("physical-test-device", "emulator-fake")]
        for arguments in invalid:
            with self.subTest(arguments=arguments), mock.patch.object(module.subprocess, "run") as run:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    module.main(arguments)
                self.assertEqual(2, error.exception.code)
                run.assert_not_called()

    def test_jni_manifest_generates_both_optins_and_distinct_receipt_field(self):
        def fake_adb(command, **kwargs):
            if command[3:6] == ["shell", "am", "instrument"]:
                kwargs["stdout"].write((event(1) + event(0) + success()).encode())
                return subprocess.CompletedProcess(command, 0)
            if command[3:5] == ["shell", "getprop"]:
                output = b"synthetic-metadata\n"
            elif command[3:6] == ["shell", "pm", "path"]:
                output = b"package:/data/app/synthetic/base.apk\n"
            elif command[3:5] == ["shell", "sha256sum"]:
                output = (module.file_sha256(self.apk) + "  /data/app/synthetic/base.apk\n").encode()
            else:
                self.fail("Unexpected mocked adb command")
            return subprocess.CompletedProcess(command, 0, stdout=output, stderr=b"")

        relative = "assigned/ko-12.json"
        with mock.patch.object(module.subprocess, "run", side_effect=fake_adb):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, module.main(self.arguments() + ["--jni-corpus-manifest", relative]))
        reports = list((self.root / "evidence").glob("*/evidence.json"))
        self.assertEqual(1, len(reports))
        report = json.loads(reports[0].read_text())
        self.assertEqual(relative, report["jni_corpus_manifest"])
        self.assertIsNone(report["corpus_manifest"])
        self.assertIsNone(report["native_corpus_manifest"])
        self.assertEqual(["-e", "jniCorpusManifest", relative, "-e", "dedicatedCorpusDevice", "true",
                          "-e", "dedicatedNativeJniProbe", "true", module.RUNNER], report["command_argv"][-10:])
        self.assertNotIn("corpusManifest", report["command_argv"])
        self.assertNotIn("nativeCorpusManifest", report["command_argv"])
        self.assertEqual("verified", report["test_apk_verification"])
        self.assertEqual("PASS", report["verdict"])


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Run synthetic Android instrumentation against a hash-verified installed Alpha APK.

This host-only tool uses Python's standard library. A successful adb exit alone is never
test evidence. Interrupted runs, missing terminals, mismatched counts and skips cannot pass.
The matching instrumentation APK must already be installed on a dedicated test device.
"""
import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import uuid

PACKAGE = "app.guidecast.transmitter.alpha"
RUNNER = PACKAGE + ".test/app.guidecast.transmitter.GuideCastTestRunner"
TEST_STATUSES = {0: "passed", -1: "error", -2: "failed", -3: "skipped", -4: "skipped"}
METADATA_TIMEOUT = 15


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_instrumentation(output, returncode=0, timed_out=False):
    """Parse runner protocol separately from JUnit prose and cross-check both ledgers."""
    bundle = {}
    declared = set()
    active = set()
    completed = set()
    tests = []
    issues = []
    terminal_codes = []
    ok_counts = []
    failure_summaries = []
    failure_banner = False

    def identity(fields):
        name, test = fields.get("class"), fields.get("test")
        if not name or not test:
            return None
        return name, test, fields.get("current", "")

    for raw_line in output.splitlines():
        line = raw_line.strip()
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field = line.removeprefix("INSTRUMENTATION_STATUS: ")
            if "=" in field:
                key, value = field.split("=", 1)
                bundle[key] = value
                if key == "numtests":
                    if value.isdigit():
                        declared.add(int(value))
                    else:
                        issues.append("Invalid numtests field")
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.split(":", 1)[1])
            except ValueError:
                issues.append("Invalid test status code")
                bundle = {}
                continue
            key = identity(bundle)
            if code == 1:
                if key is None:
                    issues.append("Test start has no class/test identity")
                elif key in active or key in completed:
                    issues.append("Duplicate test start")
                else:
                    active.add(key)
            elif code in TEST_STATUSES:
                if key is None:
                    issues.append("Test terminal has no class/test identity")
                else:
                    if key in completed:
                        issues.append("Duplicate test terminal")
                    if key not in active:
                        issues.append("Test terminal has no matching start")
                    active.discard(key)
                    completed.add(key)
                    tests.append({"class": key[0], "test": key[1], "current": key[2],
                                  "status": TEST_STATUSES[code], "status_code": code})
            else:
                issues.append("Unknown test status code: " + str(code))
            bundle = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            try:
                terminal_codes.append(int(line.split(":", 1)[1]))
            except ValueError:
                issues.append("Invalid instrumentation terminal code")
        elif line.startswith("INSTRUMENTATION_RESULT: shortMsg="):
            issues.append("Runner reported a terminal shortMsg")
        elif line == "FAILURES!!!":
            failure_banner = True
        else:
            match = re.fullmatch(r"OK \((\d+) tests?\)", line)
            if match:
                ok_counts.append(int(match.group(1)))
            match = re.fullmatch(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)(?:,\s*Errors:\s*(\d+))?", line)
            if match:
                failure_summaries.append(tuple(int(value or 0) for value in match.groups()))

    counters = {name: 0 for name in ("passed", "failed", "error", "skipped")}
    counters.update(collections.Counter(test["status"] for test in tests))
    expected = next(iter(declared)) if len(declared) == 1 else None
    if expected is None or expected <= 0:
        issues.append("Missing, zero or inconsistent declared test count")
    elif expected != len(tests):
        issues.append("Declared test count does not match terminal test count")
    if active:
        issues.append("Started tests are missing terminal statuses")
    if bundle:
        issues.append("Unterminated instrumentation status bundle")
    if terminal_codes != [-1]:
        issues.append("Missing or unsuccessful instrumentation terminal")
    failed = counters["failed"] + counters["error"]
    if failure_banner or failed:
        if not failure_banner or len(failure_summaries) != 1:
            issues.append("Test failures and JUnit failure summary disagree")
        elif failure_summaries[0][0] != len(tests) or sum(failure_summaries[0][1:]) != failed:
            issues.append("JUnit failure counters disagree with terminal statuses")
        if ok_counts:
            issues.append("Conflicting JUnit success and failure summaries")
    elif ok_counts != [len(tests)]:
        issues.append("Missing or inconsistent JUnit success count")
    if failure_summaries and not failure_banner:
        issues.append("Failure summary without failure banner")
    if returncode != 0:
        issues.append("adb exited unsuccessfully")
    if timed_out:
        issues.append("Instrumentation timed out")
    if issues:
        verdict = "INCOMPLETE" if timed_out or active or terminal_codes != [-1] else "FAIL"
    elif failed or failure_banner:
        verdict = "FAIL"
    elif counters["skipped"]:
        verdict = "SKIPPED"
    else:
        verdict = "PASS"
    return {"verdict": verdict, "declared_tests": expected, "terminal_tests": len(tests),
            "counters": counters, "tests": tests, "issues": sorted(set(issues)),
            "junit_ok_counts": ok_counts, "junit_failure_summaries": failure_summaries,
            "instrumentation_terminal_codes": terminal_codes}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--class", dest="classes", required=True, action="append",
                        help="Class or Class#method; repeat or supply a comma-separated list")
    parser.add_argument("--apk", required=True, type=pathlib.Path, help="Exact signed target APK installed for this run")
    parser.add_argument("--test-apk", type=pathlib.Path, help="Expected installed instrumentation APK; omission is recorded as unverified")
    corpus_options = parser.add_mutually_exclusive_group()
    corpus_options.add_argument("--corpus-manifest", help="Relative public/synthetic corpus manifest path")
    corpus_options.add_argument("--native-corpus-manifest", help="Relative public Korean direct-native corpus manifest path")
    corpus_options.add_argument("--jni-corpus-manifest", help="Relative public Korean JNI status-probe corpus manifest path")
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    parser.add_argument("--timeout", type=int, default=600, help="Instrumentation timeout in seconds, 30..7200")
    args = parser.parse_args(argv)
    if not 30 <= args.timeout <= 7200:
        parser.error("--timeout must be between 30 and 7200 seconds")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        parser.error("Invalid adb serial")
    classes = [item.strip() for group in args.classes for item in group.split(",")]
    if not classes or any(not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?", item) for item in classes):
        parser.error("Invalid instrumentation class or method selection")
    apk = args.apk.resolve()
    if not apk.is_file():
        parser.error("--apk must be an existing local APK")
    test_apk = args.test_apk.resolve() if args.test_apk is not None else None
    if test_apk is not None and not test_apk.is_file():
        parser.error("--test-apk must be an existing local APK")
    selected_manifest = next((value for value in (
        args.corpus_manifest, args.native_corpus_manifest, args.jni_corpus_manifest,
    ) if value is not None), None)
    if selected_manifest is not None:
        if (not re.fullmatch(r"[A-Za-z0-9_.\-/]+", selected_manifest)
                or selected_manifest.startswith("/") or ".." in selected_manifest
                or any(part in ("", ".") for part in selected_manifest.split("/"))):
            parser.error("Corpus manifest must be a safe relative test-asset path without traversal")
        if not re.fullmatch(r"emulator-[0-9]+", args.serial):
            parser.error("Public corpus inspection requires a dedicated emulator serial")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ-") + uuid.uuid4().hex[:8]
    directory = args.output_dir.resolve() / run_id
    directory.mkdir()
    log_path, report_path = directory / "instrumentation.log", directory / "evidence.json"
    log_path.write_bytes(b"")
    adb = [args.adb, "-s", args.serial]
    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(classes)]
    if selected_manifest is not None:
        manifest_argument = ("jniCorpusManifest" if args.jni_corpus_manifest is not None else
                             "corpusManifest" if args.corpus_manifest is not None else "nativeCorpusManifest")
        command += ["-e", manifest_argument, selected_manifest, "-e", "dedicatedCorpusDevice", "true"]
        if args.jni_corpus_manifest is not None:
            command += ["-e", "dedicatedNativeJniProbe", "true"]
    command += [RUNNER]
    report = {"schema_version": 1, "started_utc": utc_now(), "package": PACKAGE,
              "serial": args.serial, "requested_tests": classes, "command_argv": command,
              "apk_path": str(apk), "apk_sha256": file_sha256(apk),
              "corpus_manifest": args.corpus_manifest,
              "native_corpus_manifest": args.native_corpus_manifest,
              "jni_corpus_manifest": args.jni_corpus_manifest,
              "test_apk_verification": "pending" if test_apk is not None else "not_verified_no_expected_test_apk",
              "metadata_commands": [], "timeout_seconds": args.timeout, "verdict": "INCOMPLETE"}
    output, returncode, timed_out = b"", None, False
    running = False

    def metadata(arguments):
        actual = adb + arguments
        report["metadata_commands"].append(actual)
        value = subprocess.run(actual, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=METADATA_TIMEOUT, check=False)
        if value.returncode != 0:
            # Raw device stderr is not needed for identity verification and can contain paths.
            raise RuntimeError("An adb metadata command failed")
        return value.stdout.decode("utf-8", errors="replace").strip()

    def installed_apk_digest(package):
        paths = [line.removeprefix("package:") for line in metadata(["shell", "pm", "path", package]).splitlines()
                 if line.startswith("package:")]
        bases = [path for path in paths if path.endswith("/base.apk")]
        if not bases and len(paths) == 1:
            bases = paths
        if len(bases) != 1 or not re.fullmatch(r"/[A-Za-z0-9_./+~=,@%-]+\.apk", bases[0]):
            raise RuntimeError("Installed base APK path was absent, ambiguous or unsafe: " + package)
        installed_path = bases[0]
        match = re.fullmatch(r"([a-fA-F0-9]{64})\s+\*?(.+)", metadata(["shell", "sha256sum", installed_path]))
        if not match or match.group(2) != installed_path:
            raise RuntimeError("Installed APK digest response did not identify the observed APK: " + package)
        return installed_path, match.group(1).lower()

    try:
        report["device"] = {name: metadata(["shell", "getprop", prop]) for name, prop in (
            ("api", "ro.build.version.sdk"), ("abi", "ro.product.cpu.abi"), ("fingerprint", "ro.build.fingerprint"))}
        if any(not value for value in report["device"].values()):
            raise RuntimeError("Device identity metadata was incomplete")
        report["installed_apk_path"], report["installed_apk_sha256"] = installed_apk_digest(PACKAGE)
        if report["installed_apk_sha256"] != report["apk_sha256"]:
            raise RuntimeError("Installed target APK differs from the expected local artifact")
        if test_apk is not None:
            report["test_apk_path"] = str(test_apk)
            report["test_apk_sha256"] = file_sha256(test_apk)
            report["installed_test_apk_path"], report["installed_test_apk_sha256"] = installed_apk_digest(PACKAGE + ".test")
            if report["installed_test_apk_sha256"] != report["test_apk_sha256"]:
                report["test_apk_verification"] = "mismatch"
                raise RuntimeError("Installed instrumentation APK differs from the expected local artifact")
            report["test_apk_verification"] = "verified"
        report["artifact_verified_utc"] = utc_now()
        running = True
        report["instrumentation_started_utc"] = utc_now()
        try:
            # Stream to disk so a timeout/interrupt still preserves the protocol emitted so far.
            with log_path.open("wb") as stream:
                process = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                         timeout=args.timeout, check=False)
            returncode = process.returncode
        except subprocess.TimeoutExpired:
            timed_out = True
        output = log_path.read_bytes()
        report["result"] = parse_instrumentation(output.decode("utf-8", errors="replace"), returncode, timed_out)
        report["verdict"] = report["result"]["verdict"]
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        report["setup_error"] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    except KeyboardInterrupt:
        report["interrupted"] = True
    finally:
        if running and (timed_out or report.get("interrupted")):
            try:
                metadata(["shell", "am", "force-stop", PACKAGE])
                report["timeout_cleanup"] = "target_stopped"
            except (RuntimeError, OSError, subprocess.SubprocessError):
                report["timeout_cleanup"] = "unconfirmed"
        if running and "result" not in report:
            report["result"] = parse_instrumentation(log_path.read_text(encoding="utf-8", errors="replace"),
                                                      returncode, timed_out)
            report["result"]["verdict"] = "INCOMPLETE"
            report["result"]["issues"].append("Host did not finish collecting the run")
        report.update({"ended_utc": utc_now(), "adb_returncode": returncode, "timed_out": timed_out,
                       "stdout_log": str(log_path), "stdout_log_sha256": file_sha256(log_path)})
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(report["verdict"] + " — " + str(report_path))
    if "result" in report:
        print(json.dumps(report["result"]["counters"], sort_keys=True))
    return 0 if report["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())

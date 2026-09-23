#!/usr/bin/env python3
"""Host evidence regressions; no device, gRPC or microphone access."""
import unittest
from emulator_microphone_evidence import overall_pass, parse_test_result, validate_emulator_identity


class MicrophoneEvidenceTest(unittest.TestCase):
    def test_same_avd_name_cannot_hide_a_different_injection_serial(self):
        fields = {'avd.name': 'fixture', 'port.serial': '5556', 'grpc.port': '8554', 'grpc.token': 'synthetic'}
        with self.assertRaisesRegex(ValueError, 'different emulators'):
            validate_emulator_identity('emulator-5554', 'fixture', fields)
        validate_emulator_identity('emulator-5556', 'fixture', fields)

    def test_junit_pass_does_not_hide_transport_error_or_truncated_fixture(self):
        junit = {'verdict': 'PASS', 'declared_tests': 1}
        injection = {'transport': 'error', 'fixture_bytes_submitted': 224640}
        self.assertFalse(overall_pass(junit, injection, 224640))
        injection.update(transport='completed', fixture_bytes_submitted=224000)
        self.assertFalse(overall_pass(junit, injection, 224640))

    def test_post_test_silence_cancellation_requires_actual_complete_junit_pass(self):
        injection = {'transport': 'cancelled_after_instrumentation', 'fixture_bytes_submitted': 224640}
        for verdict in ('FAIL', 'SKIPPED', 'INCOMPLETE'):
            self.assertFalse(overall_pass({'verdict': verdict, 'declared_tests': 1}, injection, 224640))
        self.assertTrue(overall_pass({'verdict': 'PASS', 'declared_tests': 1}, injection, 224640))

    def test_plausible_ok_without_instrumentation_terminal_cannot_pass(self):
        self.assertNotEqual('PASS', parse_test_result('OK (1 test)\n', 0)['verdict'])


if __name__ == '__main__':
    unittest.main()

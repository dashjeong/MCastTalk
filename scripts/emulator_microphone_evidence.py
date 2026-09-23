"""Synthetic microphone evidence helpers, with no device or gRPC side effects."""
import importlib.util
import pathlib
import re


def validate_emulator_identity(serial, expected_avd, fields):
    if not re.fullmatch(r'emulator-[0-9]+', serial):
        raise ValueError('Only a dedicated Android emulator is supported')
    if fields.get('avd.name') != expected_avd:
        raise ValueError('The emulator does not match the expected dedicated AVD')
    if fields.get('port.serial') != serial.removeprefix('emulator-'):
        raise ValueError('The injection endpoint and adb serial refer to different emulators')
    port = fields.get('grpc.port', '')
    if not port.isdigit() or not 1 <= int(port) <= 65535 or not fields.get('grpc.token'):
        raise ValueError('The dedicated emulator has no valid authenticated injection endpoint')


def parse_test_result(output, returncode):
    spec = importlib.util.spec_from_file_location('device_evidence', pathlib.Path(__file__).with_name('run-device-evidence.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.parse_instrumentation(output, returncode=returncode)


def overall_pass(junit, injection, fixture_bytes):
    # Teardown can cancel queued trailing silence after the real UI closes AudioRecord.
    # A transport error, skipped/incomplete test or partial source never passes.
    return (junit['verdict'] == 'PASS' and junit['declared_tests'] == 1
            and injection['fixture_bytes_submitted'] == fixture_bytes
            and injection['transport'] in ('completed', 'cancelled_after_instrumentation'))

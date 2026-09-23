#!/usr/bin/env python3
"""Feed public FLEURS audio through an emulator microphone during the real note UI test.

Test tooling only. Requires grpcio and Python bindings generated from the installed Android
emulator's lib/emulator_controller.proto. Never uses a physical microphone or prints auth data.
The APK and matching instrumentation APK must already be installed on a dedicated emulator.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import time

import grpc
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc
from google.protobuf.empty_pb2 import Empty
from emulator_microphone_evidence import overall_pass, parse_test_result, validate_emulator_identity

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--emulator-ini', type=pathlib.Path, required=True)
parser.add_argument('--expected-avd', required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
fields = dict(line.split('=', 1) for line in args.emulator_ini.read_text().splitlines() if '=' in line)
fields = {key.strip(): value.strip() for key, value in fields.items()}
try:
    validate_emulator_identity(args.serial, args.expected_avd, fields)
except ValueError as error:
    parser.error(str(error))
# Only the public fixture may reach this dedicated virtual microphone. Do this before
# launching any recording UI, including when the emulator's audio hardware is enabled.
with grpc.insecure_channel('127.0.0.1:' + fields['grpc.port']) as channel:
    stub = rpc.EmulatorControllerStub(channel)
    authentication = [('authorization', 'Bearer ' + fields['grpc.token'])]
    stub.setMicrophoneState(pb.MicrophoneState(realAudioEnabled=False), metadata=authentication, timeout=10)
    if stub.getMicrophoneState(Empty(), metadata=authentication, timeout=10).realAudioEnabled:
        raise RuntimeError('Host microphone must be disabled before the synthetic fixture test')
print('Host microphone disabled; only the public fixture will be injected', flush=True)
fixture = pathlib.Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/fixtures/fleurs-ko-1959.pcm'
pcm = fixture.read_bytes()
assert hashlib.sha256(pcm).hexdigest() == 'b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f'
adb = [args.adb, '-s', args.serial]
marker = '/sdcard/Android/data/app.guidecast.transmitter.alpha/files/synthetic-note-microphone-ready.txt'
subprocess.run(adb + ['shell', 'rm', '-f', marker], check=True, timeout=15)
args.output.parent.mkdir(parents=True, exist_ok=True)
injection_state = {'mode': 'MODE_UNSPECIFIED', 'transport': 'not_started',
    'fixture_bytes_submitted': 0, 'total_bytes_submitted': 0,
    'source_generator_completed': False, 'delivery_to_android_verified': False}
receipt = {'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'serial': args.serial, 'expected_avd': args.expected_avd, 'physical_microphone': False,
    'fixture_sha256': hashlib.sha256(pcm).hexdigest(), 'fixture_bytes': len(pcm),
    'injection': injection_state}
with args.output.open('w') as log:
    process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'app.guidecast.transmitter.VoiceNoteMicrophoneJourneyDeviceTest', '-e', 'microphoneFixture', 'fleurs-ko',
        '-e', 'portableRunner', 'true',
        'app.guidecast.transmitter.alpha.test/app.guidecast.transmitter.GuideCastTestRunner'], stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 160
        while time.monotonic() < deadline and process.poll() is None:
            if subprocess.run(adb + ['shell', 'test', '-f', marker], capture_output=True, timeout=10).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Recording UI did not become ready; inspect the test output')
        print('Recording UI ready; injecting public Korean PCM into the virtual microphone', flush=True)
        audio_format = pb.AudioFormat(samplingRate=16000, channels=pb.AudioFormat.Mono,
            format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_UNSPECIFIED)
        injection_start = time.monotonic()

        def packets():
            source = bytes(32_000) + pcm + bytes(160_000)
            # The installed emulator proto defines MODE_UNSPECIFIED as blocking until
            # the emulated microphone requests samples. The earlier REAL_TIME run left
            # only a short prefix in the saved AudioRecord WAV. That experimental mode
            # permits overwrites; use server pacing to rule out client scheduling loss.
            for offset in range(0, len(source), 640):
                if process.poll() is not None:
                    return
                packet = source[offset:offset + 640]
                # Submission to gRPC is not proof the guest recorded these bytes.
                injection_state['total_bytes_submitted'] += len(packet)
                injection_state['fixture_bytes_submitted'] += max(0,
                    min(offset + len(packet), 32_000 + len(pcm)) - max(offset, 32_000))
                yield pb.AudioPacket(format=audio_format, audio=packet)
            injection_state['source_generator_completed'] = True

        with grpc.insecure_channel('127.0.0.1:' + fields['grpc.port']) as channel:
            injection = rpc.EmulatorControllerStub(channel).injectAudio.future(packets(),
                metadata=[('authorization', 'Bearer ' + fields['grpc.token'])], timeout=160)
            injection_state['transport'] = 'running'
            try:
                while process.poll() is None and not injection.done():
                    time.sleep(.2)
                if injection.done():
                    injection.result()  # Transport errors must still fail the host run.
                    injection_state['transport'] = 'completed'
                else:
                    # A successful UI journey explicitly stops AudioRecord and reopens the
                    # saved note. A blocking injector cannot drain quiet samples after that
                    # consumer closes; cancel only after instrumentation has terminated.
                    injection.cancel()
                    injection_state['transport'] = 'cancelled_after_instrumentation'
                    print('Instrumentation finished; virtual microphone stream cancelled for teardown', flush=True)
            except grpc.RpcError as error:
                injection_state['transport'] = 'error'
                # Do not write RPC details, authentication metadata or the emulator ini.
                injection_state['error_code'] = error.code().name
                raise RuntimeError('Virtual microphone transport failed: ' + error.code().name) from None
            finally:
                if not injection.done():
                    injection.cancel()
                injection_state['elapsed_seconds'] = round(time.monotonic() - injection_start, 3)
        process.wait(timeout=180)
    finally:
        if process.poll() is None:
            subprocess.run(adb + ['shell', 'am', 'force-stop', 'app.guidecast.transmitter.alpha'], check=False, timeout=15)
            process.terminate()
            process.wait(timeout=10)
        subprocess.run(adb + ['shell', 'rm', '-f', marker], check=False, timeout=15)
        log.flush()
        result = args.output.read_text()
        receipt['instrumentation'] = parse_test_result(result, process.returncode)
        receipt['instrumentation_log_sha256'] = hashlib.sha256(result.encode()).hexdigest()
        receipt['ended_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        receipt['verdict'] = 'PASS' if overall_pass(receipt['instrumentation'], injection_state, len(pcm)) else 'FAIL'
        args.output.with_suffix('.host.json').write_text(json.dumps(receipt, indent=2) + '\n')
result = args.output.read_text()
passed = receipt['verdict'] == 'PASS'
print('Microphone, live script, save and reopen:', 'PASS' if passed else 'FAIL')
raise SystemExit(0 if passed else 1)

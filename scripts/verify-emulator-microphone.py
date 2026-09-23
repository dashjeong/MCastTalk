#!/usr/bin/env python3
"""Feed public FLEURS audio through an emulator microphone during the real note UI test.

Test tooling only. Requires grpcio and Python bindings generated from the installed Android
emulator's lib/emulator_controller.proto. Never uses a physical microphone or prints auth data.
The APK and matching instrumentation APK must already be installed on a dedicated emulator.
"""
import argparse
import hashlib
import pathlib
import subprocess
import time

import grpc
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as rpc
from google.protobuf.empty_pb2 import Empty

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--emulator-ini', type=pathlib.Path, required=True)
parser.add_argument('--expected-avd', required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Only a dedicated Android emulator is supported')
fields = dict(line.split('=', 1) for line in args.emulator_ini.read_text().splitlines() if '=' in line)
fields = {key.strip(): value.strip() for key, value in fields.items()}
if fields.get('avd.name') != args.expected_avd:
    parser.error('The emulator does not match the expected dedicated AVD')
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
subprocess.run(adb + ['shell', 'rm', '-f', marker], check=True)
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('w') as log:
    process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'app.guidecast.transmitter.VoiceNoteMicrophoneJourneyDeviceTest', '-e', 'microphoneFixture', 'fleurs-ko',
        '-e', 'portableRunner', 'true',
        'app.guidecast.transmitter.alpha.test/app.guidecast.transmitter.GuideCastTestRunner'], stdout=log, stderr=log)
    try:
        deadline = time.monotonic() + 160
        while time.monotonic() < deadline and process.poll() is None:
            if subprocess.run(adb + ['shell', 'test', '-f', marker], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Recording UI did not become ready; inspect the test output')
        print('Recording UI ready; injecting public Korean PCM into the virtual microphone', flush=True)
        audio_format = pb.AudioFormat(samplingRate=16000, channels=pb.AudioFormat.Mono,
            format=pb.AudioFormat.AUD_FMT_S16, mode=pb.AudioFormat.MODE_UNSPECIFIED)

        def packets():
            source = bytes(32_000) + pcm + bytes(160_000)
            # The installed emulator proto defines MODE_UNSPECIFIED as blocking until
            # the emulated microphone requests samples. The earlier REAL_TIME run left
            # only a short prefix in the saved AudioRecord WAV. That experimental mode
            # permits overwrites; use server pacing to rule out client scheduling loss.
            for offset in range(0, len(source), 640):
                if process.poll() is not None:
                    return
                yield pb.AudioPacket(format=audio_format, audio=source[offset:offset + 640])

        with grpc.insecure_channel('127.0.0.1:' + fields['grpc.port']) as channel:
            injection = rpc.EmulatorControllerStub(channel).injectAudio.future(packets(),
                metadata=[('authorization', 'Bearer ' + fields['grpc.token'])], timeout=160)
            try:
                while process.poll() is None and not injection.done():
                    time.sleep(.2)
                if injection.done():
                    injection.result()  # Transport errors must still fail the host run.
                else:
                    # A successful UI journey explicitly stops AudioRecord and reopens the
                    # saved note. A blocking injector cannot drain quiet samples after that
                    # consumer closes; cancel only after instrumentation has terminated.
                    injection.cancel()
                    print('Instrumentation finished; virtual microphone stream cancelled for teardown', flush=True)
            finally:
                if not injection.done():
                    injection.cancel()
        process.wait(timeout=180)
    finally:
        if process.poll() is None:
            subprocess.run(adb + ['shell', 'am', 'force-stop', 'app.guidecast.transmitter.alpha'], check=False)
            process.terminate()
            process.wait(timeout=10)
        subprocess.run(adb + ['shell', 'rm', '-f', marker], check=False)
result = args.output.read_text()
passed = process.returncode == 0 and 'OK (1 test)' in result and 'FAILURES!!!' not in result and 'INSTRUMENTATION_STATUS_CODE: -3' not in result
print('Microphone, live script, save and reopen:', 'PASS' if passed else 'FAIL')
raise SystemExit(0 if passed else 1)

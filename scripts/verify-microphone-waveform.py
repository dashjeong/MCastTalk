#!/usr/bin/env python3
"""Compare the synthetic microphone WAV with the pinned public FLEURS PCM, locally.

Requires NumPy in the host test environment only; no application dependency or download.
Writes hashes, timing and numeric evidence, never audio/transcript content. A PASS means
the specified signal and timing tolerances passed, not bit identity or a physical mic test.
Thresholds are fixed here, not tuned from a submitted recording.
"""
import argparse
import hashlib
import json
import pathlib
import struct
import sys
import wave

import numpy as np

RATE = 16_000
FIXTURE_BYTES = 224_640
FIXTURE_SHA = 'b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f'
MAX_RECORDING_BYTES = RATE * 2 * 300 + 65_536
WINDOW = 1_600  # 100 ms
STRIDE = 800  # 50 ms; overlapping windows cover boundary losses.
SEARCH = 1_280  # Diagnostic local search within +/-80 ms of global alignment.
MAX_TIMING_ERROR = 8  # 0.5 ms; a 20 ms capture frame loss cannot hide in alignment.
MIN_GLOBAL_CORRELATION = 0.95
MIN_WINDOW_CORRELATION = 0.90
MIN_ACTIVE_RMS = 0.0001
MAX_EXTRA_ENERGY_RATIO = 0.05


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def correlation_curve(recording, reference):
    """Valid, DC-invariant normalized correlation for every full reference placement."""
    count = len(reference)
    if count == 0 or len(recording) < count:
        return np.empty(0)
    centered = reference - np.mean(reference)
    energy = float(np.dot(centered, centered))
    if energy <= 1e-20:
        return np.full(len(recording) - count + 1, -1.0)
    size = 1 << (len(recording) + count - 2).bit_length()
    product = np.fft.rfft(recording, size) * np.fft.rfft(centered[::-1], size)
    numerator = np.fft.irfft(product, size)[count - 1:len(recording)]
    sums = np.concatenate(([0.0], np.cumsum(recording)))
    squares = np.concatenate(([0.0], np.cumsum(recording * recording)))
    local_sum = sums[count:] - sums[:-count]
    local_energy = np.maximum(0.0, squares[count:] - squares[:-count] - local_sum * local_sum / count)
    denominator = np.sqrt(energy * local_energy)
    result = np.full(len(numerator), -1.0)
    np.divide(numerator, denominator, out=result, where=denominator > 1e-20)
    return np.clip(result, -1.0, 1.0)


def compare_signals(recording, reference):
    recording = np.asarray(recording, dtype=np.float64)
    reference = np.asarray(reference, dtype=np.float64)
    if recording.ndim != 1 or reference.ndim != 1 or not len(reference):
        raise ValueError('Signals must be nonempty mono sample vectors')
    if not np.all(np.isfinite(recording)) or not np.all(np.isfinite(reference)):
        raise ValueError('Signals must contain finite samples')
    thresholds = {'globalCorrelationMinimum': MIN_GLOBAL_CORRELATION,
        'windowCorrelationMinimum': MIN_WINDOW_CORRELATION, 'windowSamples': WINDOW,
        'strideSamples': STRIDE, 'maximumRelativeTimingErrorSamples': MAX_TIMING_ERROR,
        'activeWindowRmsMinimum': MIN_ACTIVE_RMS, 'maximumOutsideEnergyRatio': MAX_EXTRA_ENERGY_RATIO}
    report = {'sampleRate': RATE, 'referenceSamples': len(reference), 'recordingSamples': len(recording),
        'recordingDurationMs': len(recording) * 1000 / RATE, 'thresholds': thresholds,
        'bitIdentityVerified': False, 'physicalMicrophoneTest': False, 'verdict': 'FAIL'}
    issues = []
    if len(recording) < len(reference):
        report['issues'] = ['RECORDING_SHORTER_THAN_COMPLETE_FIXTURE']
        return report
    curve = correlation_curve(recording, reference)
    offset = int(np.argmax(curve))
    score = float(curve[offset])
    matched = recording[offset:offset + len(reference)]
    x = reference - np.mean(reference)
    y = matched - np.mean(matched)
    gain = float(np.dot(x, y) / max(float(np.dot(x, x)), 1e-20))
    report.update(globalCorrelation=score, offsetSamples=offset, offsetMs=offset * 1000 / RATE,
                  fittedGain=gain, fittedDc=float(np.mean(matched) - gain * np.mean(reference)))
    if score < MIN_GLOBAL_CORRELATION:
        issues.append('GLOBAL_WAVEFORM_MISMATCH')
    if gain <= 0:
        issues.append('NONPOSITIVE_SIGNAL_GAIN')
    window = min(WINDOW, len(reference))
    starts = list(range(0, len(reference) - window + 1, STRIDE))
    if starts[-1] != len(reference) - window:
        starts.append(len(reference) - window)
    windows = []
    for start in starts:
        source = reference[start:start + window]
        source_rms = float(np.sqrt(np.mean((source - np.mean(source)) ** 2)))
        row = {'sourceStartMs': start * 1000 / RATE, 'samples': window, 'referenceRms': source_rms}
        if source_rms < MIN_ACTIVE_RMS:
            row['status'] = 'LOW_SIGNAL_UNSCORABLE'
            windows.append(row)
            continue
        expected = offset + start
        low = max(0, expected - SEARCH)
        high = min(len(recording), expected + window + SEARCH)
        local = correlation_curve(recording[low:high], source)
        best = int(np.argmax(local))
        actual = low + best
        error = actual - expected
        local_score = float(local[best])
        row.update(recordingStartMs=actual * 1000 / RATE, correlation=local_score,
                   relativeTimingErrorSamples=error, status='PASS')
        if local_score < MIN_WINDOW_CORRELATION:
            row['status'] = 'WAVEFORM_MISMATCH'
        elif abs(error) > MAX_TIMING_ERROR:
            row['status'] = 'FRAME_TIMING_MISMATCH'
        windows.append(row)
    active = [row for row in windows if row['status'] != 'LOW_SIGNAL_UNSCORABLE']
    failed = [row for row in active if row['status'] != 'PASS']
    if not active:
        issues.append('NO_SCORABLE_REFERENCE_WINDOWS')
    if failed:
        issues.append('LOCAL_WAVEFORM_OR_FRAME_TIMING_MISMATCH')
    # An intact copy embedded in repeated or unrelated audio is not a clean fixture capture.
    outside = np.concatenate((recording[:offset], recording[offset + len(reference):]))
    outside_energy = float(np.sum((outside - np.mean(outside)) ** 2)) if len(outside) else 0.0
    inside_energy = float(np.dot(y, y))
    extra_ratio = outside_energy / max(inside_energy, 1e-20)
    if extra_ratio > MAX_EXTRA_ENERGY_RATIO:
        issues.append('EXTRA_AUDIO_OUTSIDE_ALIGNED_FIXTURE')
    report.update(windows=windows, activeWindows=len(active), failedWindows=len(failed),
                  lowSignalWindows=len(windows) - len(active), outsideEnergyRatio=extra_ratio,
                  issues=issues, verdict='PASS' if not issues else 'FAIL')
    return report


def read_recording(path):
    if not path.is_file() or not 44 <= path.stat().st_size <= MAX_RECORDING_BYTES:
        raise ValueError('WAV missing or outside bounded recording size')
    encoded = path.read_bytes()
    if encoded[:4] != b'RIFF' or encoded[8:12] != b'WAVE' or struct.unpack_from('<I', encoded, 4)[0] + 8 != len(encoded):
        raise ValueError('WAV has an incomplete RIFF header or trailing bytes')
    with wave.open(str(path), 'rb') as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, RATE, 'NONE'):
            raise ValueError('Expected uncompressed signed PCM16LE, mono, 16000 Hz')
        pcm = source.readframes(source.getnframes())
        if len(pcm) != source.getnframes() * 2:
            raise ValueError('WAV data chunk is truncated')
    return encoded, np.frombuffer(pcm, dtype='<i2').astype(np.float64) / 32768.0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recording', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args(argv)
    fixture = pathlib.Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/fixtures/fleurs-ko-1959.pcm'
    if args.output.resolve() in (args.recording.resolve(), fixture.resolve()):
        parser.error('The numeric report must not overwrite either audio input')
    report = {'verdict': 'FAIL', 'fixtureSha256': FIXTURE_SHA, 'criteriaVersion': 1,
              'verifierSha256': sha256(pathlib.Path(__file__).read_bytes())}
    try:
        pcm = fixture.read_bytes()
        if len(pcm) != FIXTURE_BYTES or sha256(pcm) != FIXTURE_SHA:
            raise ValueError('Pinned public FLEURS fixture failed its byte count or SHA-256 gate')
        encoded, recording = read_recording(args.recording)
        report.update(compare_signals(recording, np.frombuffer(pcm, dtype='<i2').astype(np.float64) / 32768.0))
        report.update(recordingSha256=sha256(encoded), recordingBytes=len(encoded), numpyVersion=np.__version__)
    except (ValueError, OSError, wave.Error) as error:
        # Fixed error labels only: no paths, raw audio, transcripts or environment secrets.
        report['errorType'] = type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    print(json.dumps({key: report[key] for key in ('verdict', 'globalCorrelation', 'activeWindows', 'failedWindows', 'issues', 'errorType') if key in report}))
    return 0 if report['verdict'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Synthetic waveform integrity regressions; no device or audio service access."""
import importlib.util
import pathlib
import struct
import tempfile
import unittest
import wave
import numpy as np

spec = importlib.util.spec_from_file_location('waveform', pathlib.Path(__file__).with_name('verify-microphone-waveform.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WaveformVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rng = np.random.default_rng(11957)
        # Nonperiodic band-limited source: shift ambiguity must not hide dropped frames.
        cls.source = np.convolve(rng.normal(0, 0.04, 32_000), np.ones(3) / 3, mode='same')

    def recording(self, source):
        return np.concatenate((np.zeros(4_800), source, np.zeros(8_000)))

    def test_full_signal_with_start_delay_positive_gain_and_dc_passes(self):
        result = module.compare_signals(self.recording(self.source) * 0.4 + 0.01, self.source)
        self.assertEqual('PASS', result['verdict'])
        self.assertEqual(4_800, result['offsetSamples'])
        self.assertEqual(0, result['failedWindows'])

    def test_one_dropped_20ms_frame_is_not_hidden_by_local_realignments(self):
        damaged = np.concatenate((self.source[:16_000], self.source[16_320:]))
        result = module.compare_signals(self.recording(damaged), self.source)
        self.assertEqual('FAIL', result['verdict'])
        self.assertGreater(result['failedWindows'], 0)

    def test_reordered_frames_fail_even_when_rms_and_duration_match(self):
        damaged = self.source.copy()
        damaged[8_000:8_320], damaged[20_000:20_320] = self.source[20_000:20_320], self.source[8_000:8_320]
        result = module.compare_signals(self.recording(damaged), self.source)
        self.assertEqual('FAIL', result['verdict'])
        self.assertGreater(result['failedWindows'], 0)

    def test_partial_prefix_padded_with_silence_never_passes(self):
        result = module.compare_signals(self.recording(np.concatenate((self.source[:8_000], np.zeros(40_000)))), self.source)
        self.assertEqual('FAIL', result['verdict'])

    def test_complete_copy_duplicated_in_recording_fails(self):
        result = module.compare_signals(self.recording(np.tile(self.source, 2)), self.source)
        self.assertEqual('FAIL', result['verdict'])
        self.assertIn('EXTRA_AUDIO_OUTSIDE_ALIGNED_FIXTURE', result['issues'])

    def test_reversed_polarity_and_silence_fail(self):
        for value in (-self.source, np.zeros(len(self.source))):
            self.assertEqual('FAIL', module.compare_signals(self.recording(value), self.source)['verdict'])

    def test_substantial_tail_loss_and_short_recording_fail(self):
        for value in (self.source[:-800], self.recording(self.source[:-800])):
            self.assertEqual('FAIL', module.compare_signals(value, self.source)['verdict'])

    def test_pcm_wav_format_and_truncation_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'synthetic.wav'
            with wave.open(str(path), 'wb') as output:
                output.setnchannels(1); output.setsampwidth(2); output.setframerate(16_000)
                output.writeframes((self.source * 32768).astype('<i2').tobytes())
            encoded, samples = module.read_recording(path)
            self.assertEqual(len(self.source), len(samples))
            truncated = bytearray(encoded[:-32])
            struct.pack_into('<I', truncated, 4, len(truncated) - 8)
            path.write_bytes(truncated)
            with self.assertRaisesRegex(ValueError, 'truncated'):
                module.read_recording(path)


if __name__ == '__main__':
    unittest.main()

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from mcasttalk_bootstrap.benchmark import (
    BenchmarkError,
    require_comparable,
    summarize_benchmark,
)


def evidence(candidate: str = "candidate-a") -> dict[str, object]:
    return {
        "schemaVersion": 2,
        "suiteId": "mcasttalk-gate1-asr",
        "suiteVersion": "1.0.0",
        "component": "asr",
        "candidateId": candidate,
        "candidateVersion": "pinned-revision",
        "hardwareFingerprint": "sha256:hardware-a",
        "modelSha256": "1" * 64,
        "runtimeSha256": "2" * 64,
        "settingsSha256": "3" * 64,
        "datasetSha256": "4" * 64,
        "evaluatorSha256": "5" * 64,
        "workloadSha256": "6" * 64,
        "qualityMetrics": ["wer"],
        "requiredLanguages": ["ko", "en", "ja", "zh-CN"],
        "samples": [
            {
                "caseId": f"{language}-1",
                "language": language,
                "inputDurationMs": 1000,
                "latencyMs": latency,
                "quality": {"wer": quality},
            }
            for language, latency, quality in (
                ("ko", 500, 0.10),
                ("en", 400, 0.08),
                ("ja", 600, 0.12),
                ("zh-CN", 700, 0.11),
            )
        ],
    }


class BenchmarkTests(unittest.TestCase):
    def _summarize(self, document: dict[str, object]):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "evidence.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            return summarize_benchmark(path)

    def test_summarizes_latency_rtf_and_quality_by_language(self) -> None:
        summary = self._summarize(evidence())

        self.assertEqual(4, summary.sample_count)
        self.assertEqual(550.0, summary.latency_p50_ms)
        self.assertEqual(685.0, summary.latency_p95_ms)
        self.assertAlmostEqual(0.55, summary.real_time_factor_mean or 0.0)
        self.assertEqual(0.10, summary.quality_by_language["ko"]["wer"])

    def test_rejects_missing_language_and_duplicate_case(self) -> None:
        missing = evidence()
        missing["samples"] = missing["samples"][:-1]  # type: ignore[index]
        with self.assertRaisesRegex(BenchmarkError, "Missing samples"):
            self._summarize(missing)

        duplicate = evidence()
        duplicate["samples"][1]["caseId"] = "ko-1"  # type: ignore[index]
        with self.assertRaisesRegex(BenchmarkError, "Duplicate caseId"):
            self._summarize(duplicate)

    def test_blocks_cross_hardware_comparison(self) -> None:
        first = self._summarize(evidence("candidate-a"))
        second_document = evidence("candidate-b")
        second_document["hardwareFingerprint"] = "sha256:hardware-b"
        second = self._summarize(second_document)

        with self.assertRaisesRegex(BenchmarkError, "hardwareFingerprint"):
            require_comparable([first, second])

    def test_nmt_requires_all_twelve_directed_pairs(self) -> None:
        document = evidence()
        document["component"] = "nmt"
        document["qualityMetrics"] = ["chrf"]
        languages = document["requiredLanguages"]
        document["samples"] = [
            {"caseId": f"{source}-{target}-1", "sourceLanguage": source,
             "targetLanguage": target, "latencyMs": 250, "quality": {"chrf": 70}}
            for source in languages for target in languages if source != target
        ]
        summary = self._summarize(document)
        self.assertEqual(12, len(summary.quality_by_language))
        self.assertIn("ko->ja", summary.latency_by_group)
        self.assertIsNone(summary.real_time_factor_mean)
        document["samples"].pop()
        with self.assertRaisesRegex(BenchmarkError, "directions"):
            self._summarize(document)

    def test_rejects_unpinned_or_partially_scored_evidence(self) -> None:
        document = evidence()
        document["runtimeSha256"] = "unknown"
        with self.assertRaisesRegex(BenchmarkError, "runtimeSha256"):
            self._summarize(document)
        document = evidence()
        document["qualityMetrics"] = ["wer", "cer"]
        with self.assertRaisesRegex(BenchmarkError, "qualityMetrics"):
            self._summarize(document)

    def test_comparison_requires_same_cases_dataset_and_evaluator(self) -> None:
        first = self._summarize(evidence())
        for field in ("datasetSha256", "evaluatorSha256", "workloadSha256"):
            with self.subTest(field=field):
                different = evidence("b")
                different[field] = "a" * 64
                with self.assertRaisesRegex(BenchmarkError, field):
                    require_comparable([first, self._summarize(different)])
        different = evidence("b")
        different["samples"][0]["caseId"] = "different-case"
        with self.assertRaisesRegex(BenchmarkError, "caseSetFingerprint"):
            require_comparable([first, self._summarize(different)])

    def test_tts_rtf_uses_generated_audio_duration(self) -> None:
        document = evidence()
        document["component"] = "tts"
        for sample in document["samples"]:
            sample.pop("inputDurationMs")
            sample["outputDurationMs"] = 2000
        self.assertAlmostEqual(0.275, self._summarize(document).real_time_factor_mean)
        document["samples"][0].pop("outputDurationMs")
        with self.assertRaisesRegex(BenchmarkError, "outputDurationMs"):
            self._summarize(document)

    def test_summary_is_evidence_not_automatic_release_approval(self) -> None:
        summary = self._summarize(evidence())
        self.assertFalse(summary.as_dict()["releaseApproved"])


if __name__ == "__main__":
    unittest.main()

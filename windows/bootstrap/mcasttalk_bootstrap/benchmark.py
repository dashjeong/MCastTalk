from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from statistics import fmean
from typing import Any, Iterable


COMPONENTS = {"asr", "nmt", "tts"}
HASH_FIELDS = ("modelSha256", "runtimeSha256", "settingsSha256", "datasetSha256", "evaluatorSha256", "workloadSha256")


class BenchmarkError(ValueError):
    """Raised when benchmark evidence is incomplete or cannot be compared."""


@dataclass(frozen=True)
class BenchmarkSummary:
    suite_id: str
    suite_version: str
    component: str
    candidate_id: str
    candidate_version: str
    hardware_fingerprint: str
    provenance: dict[str, str]
    case_set_fingerprint: str
    quality_metrics: tuple[str, ...]
    sample_count: int
    languages: tuple[str, ...]
    latency_p50_ms: float
    latency_p95_ms: float
    real_time_factor_mean: float | None
    quality_by_language: dict[str, dict[str, float]]
    latency_by_group: dict[str, dict[str, float | int]]

    def as_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": 2,
            "suiteId": self.suite_id,
            "suiteVersion": self.suite_version,
            "component": self.component,
            "candidateId": self.candidate_id,
            "candidateVersion": self.candidate_version,
            "hardwareFingerprint": self.hardware_fingerprint,
            "provenance": self.provenance,
            "caseSetFingerprint": self.case_set_fingerprint,
            "qualityMetrics": list(self.quality_metrics),
            "sampleCount": self.sample_count,
            "languages": list(self.languages),
            "latencyMs": {
                "p50": round(self.latency_p50_ms, 3),
                "p95": round(self.latency_p95_ms, 3),
            },
            "realTimeFactorMean": (
                round(self.real_time_factor_mean, 5)
                if self.real_time_factor_mean is not None
                else None
            ),
            "qualityByGroup": self.quality_by_language,
            "latencyByGroup": self.latency_by_group,
            "releaseApproved": False,
        }


def _required_string(value: dict[str, Any], name: str) -> str:
    result = value.get(name)
    if not isinstance(result, str) or not result.strip():
        raise BenchmarkError(f"{name} must be a non-empty string")
    return result.strip()


def _finite_number(value: Any, name: str, *, positive: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BenchmarkError(f"{name} must be a number")
    number = float(value)
    if not math.isfinite(number) or (positive and number <= 0):
        qualifier = "a positive finite" if positive else "a finite"
        raise BenchmarkError(f"{name} must be {qualifier} number")
    return number


def _percentile(values: Iterable[float], percentile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise BenchmarkError("Cannot summarize an empty sample set")
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percentile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def summarize_benchmark(path: Path) -> BenchmarkSummary:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise BenchmarkError(f"Cannot read benchmark evidence: {exc}") from exc
    if not isinstance(document, dict):
        raise BenchmarkError("Benchmark evidence must be a JSON object")
    if document.get("schemaVersion") != 2:
        raise BenchmarkError("schemaVersion must be 2; unpinned legacy evidence cannot qualify")

    provenance: dict[str, str] = {}
    for name in HASH_FIELDS:
        digest = _required_string(document, name)
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise BenchmarkError(f"{name} must be a lowercase SHA-256 digest")
        provenance[name] = digest
    required_metrics = document.get("qualityMetrics")
    if (not isinstance(required_metrics, list) or not required_metrics
            or any(not isinstance(item, str) or not item.strip() for item in required_metrics)
            or len(set(required_metrics)) != len(required_metrics)):
        raise BenchmarkError("qualityMetrics must be a non-empty unique string array")

    component = _required_string(document, "component")
    if component not in COMPONENTS:
        raise BenchmarkError(f"Unsupported component: {component}")
    required_languages = document.get("requiredLanguages")
    if (
        not isinstance(required_languages, list)
        or not required_languages
        or any(not isinstance(item, str) or not item for item in required_languages)
    ):
        raise BenchmarkError("requiredLanguages must be a non-empty string array")
    if len(set(required_languages)) != len(required_languages):
        raise BenchmarkError("requiredLanguages must not contain duplicates")
    required_groups = set(required_languages)
    if component == "nmt":
        if len(required_languages) < 2:
            raise BenchmarkError("NMT requires at least two languages")
        required_groups = {f"{source}->{target}" for source in required_languages
                           for target in required_languages if source != target}

    samples = document.get("samples")
    if not isinstance(samples, list) or not samples:
        raise BenchmarkError("samples must be a non-empty array")

    seen_case_ids: set[str] = set()
    latencies: list[float] = []
    real_time_factors: list[float] = []
    quality_values: dict[str, dict[str, list[float]]] = {}
    observed_languages: set[str] = set()
    case_identities: list[list[Any]] = []
    group_latencies: dict[str, list[float]] = {}

    for index, sample in enumerate(samples):
        prefix = f"samples[{index}]"
        if not isinstance(sample, dict):
            raise BenchmarkError(f"{prefix} must be an object")
        case_id = _required_string(sample, "caseId")
        if case_id in seen_case_ids:
            raise BenchmarkError(f"Duplicate caseId: {case_id}")
        seen_case_ids.add(case_id)
        if component == "nmt":
            source = _required_string(sample, "sourceLanguage")
            target = _required_string(sample, "targetLanguage")
            language = f"{source}->{target}"
        else:
            language = _required_string(sample, "language")
        if language not in required_groups:
            raise BenchmarkError(f"{prefix}.language is outside requiredLanguages")
        observed_languages.add(language)
        latency = _finite_number(sample.get("latencyMs"), f"{prefix}.latencyMs", positive=True)
        latencies.append(latency)
        group_latencies.setdefault(language, []).append(latency)
        identity: list[Any] = [case_id, language]

        if component in {"asr", "tts"}:
            duration_key = "inputDurationMs" if component == "asr" else "outputDurationMs"
            duration_number = _finite_number(
                sample.get(duration_key),
                f"{prefix}.{duration_key}",
                positive=True,
            )
            real_time_factors.append(latency / duration_number)
            if component == "asr":
                identity.append(duration_number)
        case_identities.append(identity)

        quality = sample.get("quality")
        if not isinstance(quality, dict) or set(quality) != set(required_metrics):
            raise BenchmarkError(f"{prefix}.quality must contain exactly qualityMetrics")
        language_metrics = quality_values.setdefault(language, {})
        for metric_name, raw_value in quality.items():
            if not isinstance(metric_name, str) or not metric_name:
                raise BenchmarkError(f"{prefix}.quality has an invalid metric name")
            metric_value = _finite_number(
                raw_value,
                f"{prefix}.quality.{metric_name}",
            )
            language_metrics.setdefault(metric_name, []).append(metric_value)

    missing = required_groups - observed_languages
    if missing:
        raise BenchmarkError(
            "Missing samples for required languages/directions: " + ", ".join(sorted(missing))
        )

    quality_summary = {
        language: {
            metric: round(fmean(values), 6)
            for metric, values in sorted(metrics.items())
        }
        for language, metrics in sorted(quality_values.items())
    }
    return BenchmarkSummary(
        suite_id=_required_string(document, "suiteId"),
        suite_version=_required_string(document, "suiteVersion"),
        component=component,
        candidate_id=_required_string(document, "candidateId"),
        candidate_version=_required_string(document, "candidateVersion"),
        hardware_fingerprint=_required_string(document, "hardwareFingerprint"),
        provenance=provenance,
        case_set_fingerprint="sha256:" + hashlib.sha256(
            json.dumps(sorted(case_identities), separators=(",", ":")).encode()
        ).hexdigest(),
        quality_metrics=tuple(sorted(required_metrics)),
        sample_count=len(samples),
        languages=tuple(sorted(required_languages)),
        latency_p50_ms=_percentile(latencies, 0.50),
        latency_p95_ms=_percentile(latencies, 0.95),
        real_time_factor_mean=(
            fmean(real_time_factors) if real_time_factors else None
        ),
        quality_by_language=quality_summary,
        latency_by_group={
            group: {"sampleCount": len(values), "p50": _percentile(values, 0.50),
                    "p95": _percentile(values, 0.95)}
            for group, values in sorted(group_latencies.items())
        },
    )


def require_comparable(summaries: Iterable[BenchmarkSummary]) -> None:
    values = list(summaries)
    if len(values) < 2:
        raise BenchmarkError("At least two summaries are required for comparison")
    baseline = values[0]
    for candidate in values[1:]:
        mismatches: list[str] = []
        if candidate.suite_id != baseline.suite_id:
            mismatches.append("suiteId")
        if candidate.suite_version != baseline.suite_version:
            mismatches.append("suiteVersion")
        if candidate.component != baseline.component:
            mismatches.append("component")
        if candidate.hardware_fingerprint != baseline.hardware_fingerprint:
            mismatches.append("hardwareFingerprint")
        if candidate.languages != baseline.languages:
            mismatches.append("requiredLanguages")
        if candidate.quality_metrics != baseline.quality_metrics:
            mismatches.append("qualityMetrics")
        if candidate.case_set_fingerprint != baseline.case_set_fingerprint:
            mismatches.append("caseSetFingerprint")
        # Runtime/model/settings are allowed experimental variables. Workload and
        # evaluator identities pin the conditions that must be held constant.
        for name in ("datasetSha256", "evaluatorSha256", "workloadSha256"):
            if candidate.provenance[name] != baseline.provenance[name]:
                mismatches.append(name)
        if mismatches:
            raise BenchmarkError(
                f"{candidate.candidate_id} is not comparable; mismatched "
                + ", ".join(mismatches)
            )

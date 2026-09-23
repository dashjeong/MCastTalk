from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable, Mapping

from .hardware import Accelerator


PORTABLE_BACKENDS = ("vulkan", "winml", "directml")
VENDOR_BACKENDS = ("openvino", "cuda", "tensorrt", "hip", "qnn")


@dataclass(frozen=True)
class BackendRoute:
    backend: str
    device_id: str
    device_name: str
    vendor: str
    portability: str
    status: str = "self-test-required"

    def as_dict(self) -> dict[str, str]:
        return {
            "backend": self.backend,
            "deviceId": self.device_id,
            "deviceName": self.device_name,
            "vendor": self.vendor,
            "portability": self.portability,
            "status": self.status,
        }


@dataclass(frozen=True)
class CalibrationEvidence:
    model_package_id: str
    model_package_version: str
    hardware_fingerprint: str
    model_sha256: str
    backend: str
    device_id: str
    driver_version: str | None
    runtime_version: str
    runtime_sha256: str
    settings_sha256: str
    correctness_passed: bool
    passed: bool
    p95_latency_ms: float
    peak_memory_bytes: float
    sustained_minutes: float
    thermal_throttle_observed: bool


@dataclass(frozen=True)
class RouteConfiguration:
    """The installed runtime and exact settings to be used for this route."""
    driver_version: str | None
    runtime_version: str
    runtime_sha256: str
    settings_sha256: str


def build_backend_routes(
    model_backends: Iterable[str],
    accelerators: Iterable[Accelerator],
) -> tuple[BackendRoute, ...]:
    """Build candidates only; no route is usable until its worker self-test passes."""
    supported = tuple(dict.fromkeys(model_backends))
    routes: list[BackendRoute] = []
    for backend in PORTABLE_BACKENDS + VENDOR_BACKENDS:
        if backend not in supported:
            continue
        for index, accelerator in enumerate(accelerators):
            if backend not in accelerator.backend_candidates:
                continue
            routes.append(
                BackendRoute(
                    backend=backend,
                    device_id=accelerator.device_id or f"probe-index:{index}",
                    device_name=accelerator.name,
                    vendor=accelerator.vendor,
                    portability=(
                        "cross-vendor" if backend in PORTABLE_BACKENDS else "vendor-specific"
                    ),
                )
            )
    if "cpu" in supported:
        routes.append(
            BackendRoute(
                backend="cpu",
                device_id="cpu:0",
                device_name="System CPU",
                vendor="CPU",
                portability="universal-fallback",
            )
        )
    return tuple(routes)


def choose_calibrated_route(
    routes: Iterable[BackendRoute],
    evidence: Iterable[CalibrationEvidence],
    *,
    model_package_id: str,
    model_package_version: str,
    hardware_fingerprint: str,
    model_sha256: str,
    route_configurations: Mapping[tuple[str, str], RouteConfiguration],
    minimum_sustained_minutes: float = 10.0,
) -> BackendRoute:
    """Select the fastest passing route by p95 latency, then peak memory.

    Evidence from a different model, driver-era hardware fingerprint, or an
    insufficient/thermally throttled sustained run cannot be selected.
    """
    if not math.isfinite(minimum_sustained_minutes) or minimum_sustained_minutes < 10:
        raise ValueError("Sustained calibration must be at least 10 minutes")
    evidence_by_route = {
        (item.backend, item.device_id): item
        for item in evidence
        if item.model_package_id == model_package_id
        and item.model_package_version == model_package_version
        and item.hardware_fingerprint == hardware_fingerprint
        and item.model_sha256 == model_sha256
    }
    passing: list[tuple[float, float, BackendRoute]] = []
    for route in routes:
        measurement = evidence_by_route.get((route.backend, route.device_id))
        configuration = route_configurations.get((route.backend, route.device_id))
        if measurement is None or configuration is None:
            continue
        if (
            measurement.passed
            and measurement.correctness_passed
            and all(math.isfinite(value) for value in (
                measurement.p95_latency_ms, measurement.peak_memory_bytes,
                measurement.sustained_minutes,
            ))
            and measurement.driver_version == configuration.driver_version
            and measurement.runtime_version == configuration.runtime_version
            and measurement.runtime_sha256 == configuration.runtime_sha256
            and measurement.settings_sha256 == configuration.settings_sha256
            and measurement.p95_latency_ms > 0
            and measurement.peak_memory_bytes >= 0
            and measurement.sustained_minutes >= minimum_sustained_minutes
            and not measurement.thermal_throttle_observed
            and measurement.runtime_version.strip()
        ):
            passing.append(
                (
                    measurement.p95_latency_ms,
                    measurement.peak_memory_bytes,
                    route,
                )
            )
    if not passing:
        raise ValueError("No backend route passed calibration")
    passing.sort(key=lambda item: (item[0], item[1]))
    return passing[0][2]

from __future__ import annotations

import unittest
from dataclasses import replace

from mcasttalk_bootstrap.backend import (
    CalibrationEvidence,
    RouteConfiguration,
    build_backend_routes,
    choose_calibrated_route,
)
from mcasttalk_bootstrap.hardware import Accelerator, GIBIBYTE


def accelerator(vendor: str, backends: tuple[str, ...]) -> Accelerator:
    return Accelerator(
        name=f"{vendor} test GPU",
        vendor=vendor,
        device_id=f"PCI:{vendor}",
        memory_bytes=8 * GIBIBYTE,
        memory_kind="reported",
        driver_version="test",
        compute_capability=None,
        backend_candidates=backends,
        probe_sources=("test",),
    )


class BackendTests(unittest.TestCase):
    def test_builds_cross_vendor_routes_before_cpu_fallback(self) -> None:
        routes = build_backend_routes(
            ("cpu", "vulkan", "directml", "cuda"),
            (
                accelerator("AMD", ("vulkan", "directml", "hip")),
                accelerator("Intel", ("vulkan", "directml", "openvino")),
                accelerator("NVIDIA", ("vulkan", "directml", "cuda")),
            ),
        )

        self.assertEqual("vulkan", routes[0].backend)
        self.assertEqual(3, sum(route.backend == "vulkan" for route in routes))
        self.assertEqual("cpu", routes[-1].backend)
        self.assertTrue(all(route.status == "self-test-required" for route in routes))

    def test_selects_measured_best_not_vendor_name(self) -> None:
        routes = build_backend_routes(
            ("cpu", "vulkan", "directml"),
            (accelerator("AMD", ("vulkan", "directml")),),
        )
        evidence = [
            calibration("vulkan", "PCI:AMD", 420.0, 3 * GIBIBYTE),
            calibration("directml", "PCI:AMD", 510.0, 2 * GIBIBYTE),
            calibration("cpu", "cpu:0", 900.0, 1 * GIBIBYTE),
        ]

        selected = choose_calibrated_route(
            routes,
            evidence,
            model_package_id="model-a",
            model_package_version="1",
            hardware_fingerprint="hardware-a",
            model_sha256="1" * 64,
            route_configurations=configurations(routes),
        )

        self.assertEqual("vulkan", selected.backend)
        self.assertEqual("AMD", selected.vendor)

    def test_rejects_unmeasured_or_failed_routes(self) -> None:
        routes = build_backend_routes(
            ("cpu", "vulkan"),
            (accelerator("AMD", ("vulkan",)),),
        )
        with self.assertRaisesRegex(ValueError, "passed calibration"):
            choose_calibrated_route(
                routes,
                [calibration("vulkan", "PCI:AMD", 1.0, 1.0, passed=False)],
                model_package_id="model-a",
                model_package_version="1",
                hardware_fingerprint="hardware-a",
                model_sha256="1" * 64,
                route_configurations=configurations(routes),
            )

    def test_rejects_stale_or_thermally_throttled_calibration(self) -> None:
        routes = build_backend_routes(
            ("cpu", "vulkan"),
            (accelerator("AMD", ("vulkan",)),),
        )
        stale = calibration("vulkan", "PCI:AMD", 10.0, 1.0)
        throttled = calibration(
            "cpu",
            "cpu:0",
            20.0,
            1.0,
            thermal=True,
        )
        with self.assertRaisesRegex(ValueError, "passed calibration"):
            choose_calibrated_route(
                routes,
                [stale, throttled],
                model_package_id="model-a",
                model_package_version="2",
                hardware_fingerprint="hardware-a",
                model_sha256="1" * 64,
                route_configurations=configurations(routes),
            )

    def test_rejects_wrong_runtime_settings_correctness_and_nonfinite_metrics(self) -> None:
        routes = build_backend_routes(("cpu",), ())
        good = calibration("cpu", "cpu:0", 100, 1024)
        for field, value in (
            ("runtime_sha256", "9" * 64), ("settings_sha256", "9" * 64),
            ("model_sha256", "9" * 64), ("driver_version", "new-driver"),
            ("correctness_passed", False), ("p95_latency_ms", float("inf")),
            ("sustained_minutes", 1), ("thermal_throttle_observed", True),
        ):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "passed calibration"):
                choose_calibrated_route(
                    routes, [replace(good, **{field: value})],
                    model_package_id="model-a", model_package_version="1",
                    hardware_fingerprint="hardware-a", model_sha256="1" * 64,
                    route_configurations=configurations(routes),
                )


def calibration(
    backend: str,
    device_id: str,
    latency: float,
    memory: float,
    *,
    passed: bool = True,
    thermal: bool = False,
) -> CalibrationEvidence:
    return CalibrationEvidence(
        model_package_id="model-a",
        model_package_version="1",
        hardware_fingerprint="hardware-a",
        model_sha256="1" * 64,
        backend=backend,
        device_id=device_id,
        driver_version="driver-a",
        runtime_version="runtime-a",
        runtime_sha256="2" * 64,
        settings_sha256="3" * 64,
        correctness_passed=True,
        passed=passed,
        p95_latency_ms=latency,
        peak_memory_bytes=memory,
        sustained_minutes=15.0,
        thermal_throttle_observed=thermal,
    )


def configurations(routes):
    return {(route.backend, route.device_id): RouteConfiguration(
        driver_version="driver-a", runtime_version="runtime-a",
        runtime_sha256="2" * 64, settings_sha256="3" * 64,
    ) for route in routes}


if __name__ == "__main__":
    unittest.main()

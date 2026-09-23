from __future__ import annotations

import unittest
from dataclasses import replace

from mcasttalk_bootstrap.hardware import (
    Accelerator,
    GIBIBYTE,
    HardwareProfile,
    classify_tier,
    merge_accelerators,
    merge_generic_accelerators,
    parse_nvidia_smi_output,
    parse_vulkaninfo_json,
    parse_vulkaninfo_summary,
    parse_windows_video_controllers,
)


class HardwareTests(unittest.TestCase):
    def test_parses_nvidia_smi(self) -> None:
        values = parse_nvidia_smi_output(
            "NVIDIA RTX 4090, 24564, 555.42, 8.9, GPU-abc123\n"
        )
        self.assertEqual(len(values), 1)
        self.assertEqual(values[0].vendor, "NVIDIA")
        self.assertEqual(values[0].memory_bytes, 24564 * 1024**2)
        self.assertEqual(values[0].compute_capability, "8.9")
        self.assertEqual(values[0].device_id, "nvidia:GPU-abc123")

    def test_performance_tier_requires_ram_and_vram(self) -> None:
        gpu = Accelerator(
            name="Test GPU",
            vendor="AMD",
            device_id="PCI\\VEN_1002",
            memory_bytes=24 * GIBIBYTE,
            memory_kind="dedicated",
            driver_version=None,
            compute_capability=None,
            backend_candidates=("vulkan", "winml", "directml"),
            probe_sources=("test",),
        )
        tier, notes = classify_tier(64 * GIBIBYTE, (gpu,))
        self.assertEqual(tier, "performance")
        self.assertTrue(notes)

    def test_cpu_tier_when_no_supported_gpu(self) -> None:
        tier, _ = classify_tier(64 * GIBIBYTE, ())
        self.assertEqual(tier, "cpu-only")

    def test_discovers_amd_and_intel_portable_backends(self) -> None:
        controllers = parse_windows_video_controllers(
            """
            [
              {"Name":"AMD Radeon RX 7900 XTX","AdapterCompatibility":"Advanced Micro Devices, Inc.","AdapterRAM":17179869184,"DriverVersion":"1.2.3","PNPDeviceID":"PCI\\\\VEN_1002"},
              {"Name":"Intel(R) Arc(TM) A770 Graphics","AdapterCompatibility":"Intel Corporation","AdapterRAM":8589934592,"DriverVersion":"4.5.6","PNPDeviceID":"PCI\\\\VEN_8086"}
            ]
            """
        )

        self.assertEqual({"AMD", "Intel"}, {item.vendor for item in controllers})
        amd = next(item for item in controllers if item.vendor == "AMD")
        intel = next(item for item in controllers if item.vendor == "Intel")
        self.assertIn("vulkan", amd.backend_candidates)
        self.assertIn("directml", amd.backend_candidates)
        self.assertNotIn("hip", amd.backend_candidates)
        self.assertIn("winml", amd.backend_candidates)
        self.assertIn("vulkan", intel.backend_candidates)
        self.assertIn("openvino", intel.backend_candidates)

    def test_filters_software_display_adapter(self) -> None:
        values = parse_windows_video_controllers(
            '{"Name":"Microsoft Basic Display Adapter","AdapterRAM":0}'
        )
        self.assertEqual((), values)

    def test_treats_wmi_uint32_vram_sentinel_as_unknown(self) -> None:
        values = parse_windows_video_controllers(
            '{"Name":"AMD Radeon Test","AdapterCompatibility":"AMD",'
            '"AdapterRAM":4294967295}'
        )

        self.assertEqual(0, values[0].memory_bytes)
        self.assertEqual("unknown", values[0].memory_kind)

    def test_discovers_amd_gpu_from_vulkan_when_wmi_is_unavailable(self) -> None:
        values = parse_vulkaninfo_summary(
            """
            Devices:
            ========
            GPU0:
                apiVersion = 4202692 (1.2.196)
                driverVersion = 8388812 (0x8000cc)
                vendorID = 0x1002
                deviceID = 0x67ef
                deviceType = PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
                deviceName = Radeon Pro 560X
                driverInfo = 21.30.45.22
            """
        )

        self.assertEqual(1, len(values))
        self.assertEqual("AMD", values[0].vendor)
        self.assertEqual("Radeon Pro 560X", values[0].name)
        self.assertIn("vulkan", values[0].backend_candidates)
        self.assertEqual(("vulkaninfo",), values[0].probe_sources)

    def test_reads_device_local_memory_from_vulkan_json(self) -> None:
        value = parse_vulkaninfo_json(
            """
            {
              "VkPhysicalDeviceProperties": {
                "vendorID": 4098,
                "deviceID": 26607,
                "deviceType": 2,
                "deviceName": "Radeon Pro 560X",
                "driverVersion": 8388812,
                "pipelineCacheUUID": [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15]
              },
              "VkPhysicalDeviceMemoryProperties": {
                "memoryHeaps": [
                  {"flags": 3, "size": 4026531840},
                  {"flags": 0, "size": 8265924608},
                  {"flags": 3, "size": 268435456}
                ]
              }
            }
            """
        )

        self.assertIsNotNone(value)
        assert value is not None
        self.assertEqual(4 * GIBIBYTE, value.memory_bytes)
        self.assertEqual("dedicated", value.memory_kind)
        self.assertEqual(("vulkaninfo-json",), value.probe_sources)
        self.assertIsNotNone(value.device_id)
        self.assertTrue(
            (value.device_id or "").endswith("000102030405060708090a0b0c0d0e0f:index:0")
        )

    def test_preserves_two_identically_named_vulkan_devices(self) -> None:
        first = parse_vulkaninfo_summary(
            "GPU0:\n vendorID = 0x1002\n deviceID = 0x67ef\n"
            " deviceName = Radeon Pro 560X"
        )[0]
        second = replace(first, device_id="vulkan:0x1002:0x67ef:1")

        merged = merge_generic_accelerators((first, second))

        self.assertEqual(2, len(merged))

    def test_partial_vulkan_summary_does_not_crash(self) -> None:
        values = parse_vulkaninfo_summary("GPU0:\n deviceName = Unknown GPU")
        self.assertEqual(1, len(values))
        self.assertEqual("Unknown", values[0].vendor)

    def test_preserves_two_identically_named_nvidia_devices(self) -> None:
        values = parse_nvidia_smi_output(
            "NVIDIA RTX 4090, 24564, 555, 8.9, GPU-one\n"
            "NVIDIA RTX 4090, 24564, 555, 8.9, GPU-two\n"
        )
        merged = merge_accelerators((), values)
        self.assertEqual(2, len(merged))
        self.assertEqual({"nvidia:GPU-one", "nvidia:GPU-two"}, {item.device_id for item in merged})

    def test_merges_wmi_and_vulkan_views_of_same_gpu(self) -> None:
        wmi = parse_windows_video_controllers(
            '{"Name":"Radeon Pro 560X","AdapterCompatibility":"AMD",'
            '"AdapterRAM":4294967296,"DriverVersion":"wmi",'
            '"PNPDeviceID":"PCI\\\\VEN_1002"}'
        )
        vulkan = parse_vulkaninfo_summary(
            "GPU0:\n vendorID = 0x1002\n deviceID = 0x67ef\n"
            " deviceName = Radeon Pro 560X\n driverInfo = vk-driver"
        )

        merged = merge_generic_accelerators(wmi + vulkan)

        self.assertEqual(1, len(merged))
        self.assertEqual(4 * GIBIBYTE, merged[0].memory_bytes)
        self.assertEqual(
            ("win32-video-controller", "vulkaninfo"),
            merged[0].probe_sources,
        )

    def test_merges_nvidia_sources_without_losing_device_identity(self) -> None:
        generic = parse_windows_video_controllers(
            '{"Name":"NVIDIA RTX 4090","AdapterCompatibility":"NVIDIA",'
            '"AdapterRAM":4294967295,"DriverVersion":"old",'
            '"PNPDeviceID":"PCI\\\\VEN_10DE"}'
        )
        detailed = parse_nvidia_smi_output("NVIDIA RTX 4090, 24564, 555.42, 8.9")

        merged = merge_accelerators(generic, detailed)

        self.assertEqual(1, len(merged))
        self.assertEqual(24564 * 1024**2, merged[0].memory_bytes)
        self.assertEqual("PCI\\VEN_10DE", merged[0].device_id)
        self.assertEqual(
            ("win32-video-controller", "nvidia-smi"),
            merged[0].probe_sources,
        )

    def test_nvidia_uuid_replaces_less_stable_wmi_identity(self) -> None:
        generic = parse_windows_video_controllers(
            '{"Name":"NVIDIA RTX 4090","AdapterCompatibility":"NVIDIA",'
            '"PNPDeviceID":"PCI\\\\VEN_10DE"}'
        )
        detailed = parse_nvidia_smi_output(
            "NVIDIA RTX 4090, 24564, 555.42, 8.9, GPU-stable\n"
        )

        merged = merge_accelerators(generic, detailed)

        self.assertEqual("nvidia:GPU-stable", merged[0].device_id)

    def test_does_not_merge_similarly_named_nvidia_models(self) -> None:
        generic = parse_windows_video_controllers(
            '{"Name":"NVIDIA RTX 3080","AdapterCompatibility":"NVIDIA",'
            '"PNPDeviceID":"PCI\\\\VEN_10DE&DEV_2206"}'
        )
        detailed = parse_nvidia_smi_output(
            "NVIDIA RTX 3080 Ti, 12288, 555.42, 8.6, GPU-other\n"
        )

        self.assertEqual(2, len(merge_accelerators(generic, detailed)))

    def test_hardware_fingerprint_is_capture_time_independent_and_driver_aware(self) -> None:
        accelerator = Accelerator(
            name="Radeon Pro 560X",
            vendor="AMD",
            device_id="vulkan:device-uuid",
            memory_bytes=4 * GIBIBYTE,
            memory_kind="dedicated",
            driver_version="driver-a",
            compute_capability=None,
            backend_candidates=("vulkan", "winml", "directml"),
            probe_sources=("vulkaninfo-json",),
        )
        first = HardwareProfile(
            captured_at="2026-09-19T00:00:00+00:00",
            os_description="Windows 11 build test",
            cpu="test cpu",
            logical_cores=12,
            memory_bytes=16 * GIBIBYTE,
            accelerators=(accelerator,),
            provisional_tier="entry-accelerated",
            notes=("one",),
        )
        recaptured = replace(first, captured_at="2026-09-20T00:00:00+00:00")
        driver_changed = replace(
            first,
            accelerators=(replace(accelerator, driver_version="driver-b"),),
        )

        self.assertEqual(first.fingerprint, recaptured.fingerprint)
        self.assertNotEqual(first.fingerprint, driver_changed.fingerprint)
        self.assertEqual(first.fingerprint, first.as_dict()["fingerprint"])


if __name__ == "__main__":
    unittest.main()

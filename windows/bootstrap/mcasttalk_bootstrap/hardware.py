from __future__ import annotations

import ctypes
import hashlib
import json
import os
import platform
import shutil
import subprocess
import tempfile
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


GIBIBYTE = 1024**3
MAX_PROBE_OUTPUT_CHARS = 16 * 1024 * 1024


@dataclass(frozen=True)
class Accelerator:
    name: str
    vendor: str
    device_id: str | None
    memory_bytes: int
    memory_kind: str
    driver_version: str | None
    compute_capability: str | None
    backend_candidates: tuple[str, ...]
    probe_sources: tuple[str, ...]

    def as_dict(self) -> dict[str, object]:
        value = asdict(self)
        value["deviceId"] = value.pop("device_id")
        value["memoryBytes"] = value.pop("memory_bytes")
        value["memoryKind"] = value.pop("memory_kind")
        value["driverVersion"] = value.pop("driver_version")
        value["computeCapability"] = value.pop("compute_capability")
        value["backendCandidates"] = list(value.pop("backend_candidates"))
        value["probeSources"] = list(value.pop("probe_sources"))
        return value


@dataclass(frozen=True)
class HardwareProfile:
    captured_at: str
    os_description: str
    cpu: str
    logical_cores: int
    memory_bytes: int
    accelerators: tuple[Accelerator, ...]
    provisional_tier: str
    notes: tuple[str, ...]

    @property
    def fingerprint(self) -> str:
        return hardware_fingerprint(
            os_description=self.os_description,
            cpu=self.cpu,
            logical_cores=self.logical_cores,
            memory_bytes=self.memory_bytes,
            accelerators=self.accelerators,
        )

    def as_dict(self) -> dict[str, object]:
        return {
            "schemaVersion": 2,
            "fingerprint": self.fingerprint,
            "capturedAt": self.captured_at,
            "os": self.os_description,
            "cpu": self.cpu,
            "logicalCores": self.logical_cores,
            "memoryBytes": self.memory_bytes,
            "accelerators": [item.as_dict() for item in self.accelerators],
            "provisionalTier": self.provisional_tier,
            "calibrationRequired": True,
            "notes": list(self.notes),
        }

    def to_json(self) -> str:
        return json.dumps(self.as_dict(), ensure_ascii=False, indent=2)


class _MemoryStatusEx(ctypes.Structure):
    _fields_ = [
        ("length", ctypes.c_ulong),
        ("memory_load", ctypes.c_ulong),
        ("total_physical", ctypes.c_ulonglong),
        ("available_physical", ctypes.c_ulonglong),
        ("total_page_file", ctypes.c_ulonglong),
        ("available_page_file", ctypes.c_ulonglong),
        ("total_virtual", ctypes.c_ulonglong),
        ("available_virtual", ctypes.c_ulonglong),
        ("available_extended_virtual", ctypes.c_ulonglong),
    ]


def hardware_fingerprint(
    *,
    os_description: str,
    cpu: str,
    logical_cores: int,
    memory_bytes: int,
    accelerators: Iterable[Accelerator],
) -> str:
    """Build a stable, driver-aware identity for calibration evidence."""
    accelerator_documents = [
        {
            "name": item.name,
            "vendor": item.vendor,
            "deviceId": item.device_id,
            "memoryBytes": item.memory_bytes,
            "memoryKind": item.memory_kind,
            "driverVersion": item.driver_version,
            "computeCapability": item.compute_capability,
            "backendCandidates": sorted(item.backend_candidates),
        }
        for item in accelerators
    ]
    accelerator_documents.sort(
        key=lambda item: (
            str(item["vendor"]),
            str(item["deviceId"]),
            str(item["name"]),
        )
    )
    canonical = json.dumps(
        {
            "os": os_description,
            "cpu": cpu,
            "logicalCores": logical_cores,
            "memoryBytes": memory_bytes,
            "accelerators": accelerator_documents,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(canonical).hexdigest()}"


def _windows_system_directory() -> Path | None:
    if os.name != "nt":
        return None
    buffer = ctypes.create_unicode_buffer(32768)
    try:
        length = ctypes.windll.kernel32.GetSystemDirectoryW(buffer, len(buffer))
    except (AttributeError, OSError):
        return None
    if length <= 0 or length >= len(buffer):
        return None
    value = Path(buffer.value)
    return value if value.is_absolute() else None


def _trusted_probe_executable(name: str) -> str | None:
    """Avoid user-shadowed PATH executables in the installed Windows host."""
    if os.name != "nt":
        return shutil.which(name.removesuffix(".exe"))
    system_directory = _windows_system_directory()
    if system_directory is None:
        return None
    lowered = name.casefold()
    if lowered == "powershell.exe":
        candidates = [
            system_directory / "WindowsPowerShell" / "v1.0" / "powershell.exe"
        ]
    elif lowered == "nvidia-smi.exe":
        candidates = [
            system_directory / "nvidia-smi.exe",
            Path(system_directory.anchor)
            / "Program Files"
            / "NVIDIA Corporation"
            / "NVSMI"
            / "nvidia-smi.exe",
        ]
    else:
        candidates = [system_directory / name]
    for candidate in candidates:
        if candidate.is_absolute() and candidate.is_file():
            return str(candidate)
    return None


def _run_probe(
    command: list[str],
    *,
    timeout: float,
    encoding: str,
    cwd: str | None = None,
) -> subprocess.CompletedProcess[str] | None:
    """Run a diagnostic with bounded captured output."""
    try:
        with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
            completed = subprocess.run(
                command,
                stdout=stdout_file,
                stderr=stderr_file,
                check=False,
                timeout=timeout,
                cwd=cwd,
            )
            stdout_file.seek(0)
            stdout_bytes = stdout_file.read(MAX_PROBE_OUTPUT_CHARS + 1)
            stderr_file.seek(0)
            stderr_bytes = stderr_file.read(MAX_PROBE_OUTPUT_CHARS + 1)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if (
        len(stdout_bytes) > MAX_PROBE_OUTPUT_CHARS
        or len(stderr_bytes) > MAX_PROBE_OUTPUT_CHARS
    ):
        return None
    return subprocess.CompletedProcess(
        command,
        completed.returncode,
        stdout_bytes.decode(encoding, errors="replace"),
        stderr_bytes.decode(encoding, errors="replace"),
    )


def total_memory_bytes() -> int:
    if os.name == "nt":
        status = _MemoryStatusEx()
        status.length = ctypes.sizeof(_MemoryStatusEx)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return int(status.total_physical)
        return 0
    try:
        return int(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"))
    except (AttributeError, OSError, ValueError):
        return 0


def parse_nvidia_smi_output(output: str) -> tuple[Accelerator, ...]:
    accelerators: list[Accelerator] = []
    for raw_line in output.splitlines():
        if not raw_line.strip():
            continue
        fields = [part.strip() for part in raw_line.split(",")]
        if len(fields) < 3:
            continue
        name, memory_mib, driver = fields[:3]
        compute = fields[3] if len(fields) > 3 and fields[3] not in {"", "N/A"} else None
        uuid = fields[4] if len(fields) > 4 and fields[4] not in {"", "N/A"} else None
        try:
            memory_bytes = int(float(memory_mib)) * 1024**2
        except ValueError:
            memory_bytes = 0
        accelerators.append(
            Accelerator(
                name=name,
                vendor="NVIDIA",
                device_id=f"nvidia:{uuid}" if uuid else None,
                memory_bytes=memory_bytes,
                memory_kind="dedicated",
                driver_version=driver or None,
                compute_capability=compute,
                backend_candidates=backend_candidates_for_vendor("NVIDIA"),
                probe_sources=("nvidia-smi",),
            )
        )
    return tuple(accelerators)


def _probe_nvidia() -> tuple[Accelerator, ...]:
    executable = _trusted_probe_executable("nvidia-smi.exe")
    if executable is None:
        return ()
    command = [
        executable,
        "--query-gpu=name,memory.total,driver_version,compute_cap,uuid",
        "--format=csv,noheader,nounits",
    ]
    completed = _run_probe(command, timeout=5, encoding="utf-8")
    if completed is None:
        return ()
    if completed.returncode != 0:
        fallback = command.copy()
        fallback[1] = "--query-gpu=name,memory.total,driver_version"
        completed = _run_probe(fallback, timeout=5, encoding="utf-8")
        if completed is None:
            return ()
    return parse_nvidia_smi_output(completed.stdout) if completed.returncode == 0 else ()


def _normalized_vendor(*values: str) -> str:
    combined = " ".join(values).casefold()
    if "nvidia" in combined:
        return "NVIDIA"
    if "advanced micro devices" in combined or "amd" in combined or "radeon" in combined:
        return "AMD"
    if "intel" in combined:
        return "Intel"
    if "qualcomm" in combined or "adreno" in combined:
        return "Qualcomm"
    return "Unknown"


def backend_candidates_for_vendor(vendor: str) -> tuple[str, ...]:
    """Return possible backends, not proof that their runtime self-test passed."""
    portable = ("vulkan", "winml", "directml")
    vendor_specific = {
        "NVIDIA": ("cuda", "tensorrt"),
        # HIP is added only by a verified runtime probe. Older Radeon devices
        # must not be treated as HIP-capable based on vendor name alone.
        "AMD": (),
        "Intel": ("openvino",),
        "Qualcomm": ("qnn",),
    }
    return portable + vendor_specific.get(vendor, ())


def parse_windows_video_controllers(output: str) -> tuple[Accelerator, ...]:
    if not output.strip():
        return ()
    try:
        decoded = json.loads(output)
    except json.JSONDecodeError:
        return ()
    entries = decoded if isinstance(decoded, list) else [decoded]
    accelerators: list[Accelerator] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        name = str(entry.get("Name") or "").strip()
        compatibility = str(entry.get("AdapterCompatibility") or "").strip()
        lowered = f"{name} {compatibility}".casefold()
        if not name or any(
            marker in lowered
            for marker in (
                "microsoft basic display",
                "remote display",
                "indirect display",
            )
        ):
            continue
        vendor = _normalized_vendor(name, compatibility)
        raw_memory = entry.get("AdapterRAM")
        try:
            memory = max(0, int(raw_memory or 0))
        except (TypeError, ValueError):
            memory = 0
        if memory == 0xFFFFFFFF:
            memory = 0
        accelerators.append(
            Accelerator(
                name=name,
                vendor=vendor,
                device_id=str(entry.get("PNPDeviceID") or "").strip() or None,
                memory_bytes=memory,
                memory_kind="reported" if memory else "unknown",
                driver_version=str(entry.get("DriverVersion") or "").strip() or None,
                compute_capability=None,
                backend_candidates=backend_candidates_for_vendor(vendor),
                probe_sources=("win32-video-controller",),
            )
        )
    return tuple(accelerators)


def _probe_windows_video_controllers() -> tuple[Accelerator, ...]:
    if os.name != "nt":
        return ()
    powershell = _trusted_probe_executable("powershell.exe")
    if powershell is None:
        return ()
    script = (
        "Get-CimInstance Win32_VideoController | "
        "Select-Object Name,AdapterCompatibility,AdapterRAM,DriverVersion,PNPDeviceID | "
        "ConvertTo-Json -Compress"
    )
    completed = _run_probe(
        [powershell, "-NoProfile", "-NonInteractive", "-Command", script],
        timeout=8,
        encoding="utf-8-sig",
    )
    if completed is None:
        return ()
    return (
        parse_windows_video_controllers(completed.stdout)
        if completed.returncode == 0
        else ()
    )


def _vendor_from_pci_id(value: str) -> str:
    return {
        "0x10de": "NVIDIA",
        "0x1002": "AMD",
        "0x8086": "Intel",
        "0x5143": "Qualcomm",
    }.get(value.casefold(), "Unknown")


def parse_vulkaninfo_summary(output: str) -> tuple[Accelerator, ...]:
    entries: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if line.startswith("GPU") and line.endswith(":"):
            if current:
                entries.append(current)
            current = {"index": line[3:-1]}
            continue
        if current is None or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        current[key] = value
    if current:
        entries.append(current)

    accelerators: list[Accelerator] = []
    for entry in entries:
        name = entry.get("deviceName", "").strip()
        vendor_id = (entry.get("vendorID", "").split() or ["0x0000"])[0]
        device_id = (entry.get("deviceID", "").split() or ["0x0000"])[0]
        if not name:
            continue
        vendor = _vendor_from_pci_id(vendor_id)
        accelerators.append(
            Accelerator(
                name=name,
                vendor=vendor,
                device_id=(
                    f"vulkan:{vendor_id}:{device_id}:{entry.get('index', '0')}"
                ),
                memory_bytes=0,
                memory_kind="unknown",
                driver_version=entry.get("driverInfo") or entry.get("driverVersion"),
                compute_capability=None,
                backend_candidates=backend_candidates_for_vendor(vendor),
                probe_sources=("vulkaninfo",),
            )
        )
    return tuple(accelerators)


def _vulkan_json_sections(
    document: dict[str, object],
) -> tuple[dict[str, object] | None, dict[str, object] | None, dict[str, object] | None]:
    properties = document.get("VkPhysicalDeviceProperties")
    memory_properties = document.get("VkPhysicalDeviceMemoryProperties")
    id_properties = document.get("VkPhysicalDeviceIDProperties")
    if isinstance(properties, dict):
        return (
            properties,
            memory_properties if isinstance(memory_properties, dict) else None,
            id_properties if isinstance(id_properties, dict) else None,
        )

    # Some Vulkan Profiles builds wrap device capability sections.
    capabilities = document.get("capabilities")
    if not isinstance(capabilities, dict):
        return None, None, None
    device = capabilities.get("device")
    if not isinstance(device, dict):
        return None, None, None
    properties_container = device.get("properties")
    if not isinstance(properties_container, dict):
        properties_container = device
    properties = properties_container.get("VkPhysicalDeviceProperties")
    memory_properties = properties_container.get("VkPhysicalDeviceMemoryProperties")
    id_properties = properties_container.get("VkPhysicalDeviceIDProperties")
    return (
        properties if isinstance(properties, dict) else None,
        memory_properties if isinstance(memory_properties, dict) else None,
        id_properties if isinstance(id_properties, dict) else None,
    )


def _vulkan_uuid(value: object) -> str | None:
    if (
        isinstance(value, list)
        and len(value) == 16
        and all(isinstance(item, int) and 0 <= item <= 255 for item in value)
    ):
        return "".join(f"{item:02x}" for item in value)
    if isinstance(value, str):
        normalized = value.replace("-", "").strip().casefold()
        if len(normalized) == 32 and all(character in "0123456789abcdef" for character in normalized):
            return normalized
    return None


def parse_vulkaninfo_json(output: str, index: int = 0) -> Accelerator | None:
    if len(output) > MAX_PROBE_OUTPUT_CHARS:
        return None
    try:
        document = json.loads(output)
    except json.JSONDecodeError:
        return None
    if not isinstance(document, dict):
        return None
    properties, memory_properties, id_properties = _vulkan_json_sections(document)
    if not isinstance(properties, dict):
        return None
    name = str(properties.get("deviceName") or "").strip()
    if not name:
        return None
    try:
        vendor_number = int(properties.get("vendorID", 0))
        device_number = int(properties.get("deviceID", 0))
    except (TypeError, ValueError):
        vendor_number = 0
        device_number = 0
    vendor_id = f"0x{vendor_number:04x}"
    device_id = f"0x{device_number:04x}"
    device_type = properties.get("deviceType")
    device_local_bytes = 0
    if isinstance(memory_properties, dict):
        heaps = memory_properties.get("memoryHeaps")
        if isinstance(heaps, list):
            for heap in heaps:
                if not isinstance(heap, dict):
                    continue
                try:
                    flags = int(heap.get("flags", 0))
                    size = int(heap.get("size", 0))
                except (TypeError, ValueError):
                    continue
                if flags & 0x1:
                    device_local_bytes += max(0, size)
    vendor = _vendor_from_pci_id(vendor_id)
    stable_suffix = (
        _vulkan_uuid(id_properties.get("deviceUUID") if id_properties else None)
        or _vulkan_uuid(properties.get("deviceUUID"))
    )
    if stable_suffix is None:
        # A pipeline-cache UUID identifies compatibility, not a physical card.
        # Preserve the enumeration index to avoid aliasing identical GPUs.
        cache_uuid = _vulkan_uuid(properties.get("pipelineCacheUUID")) or "unknown"
        stable_suffix = f"cache:{cache_uuid}:index:{index}"
    if device_local_bytes == 0:
        memory_kind = "unknown"
    elif device_type == 1:
        memory_kind = "shared"
    else:
        memory_kind = "dedicated"
    return Accelerator(
        name=name,
        vendor=vendor,
        device_id=f"vulkan:{vendor_id}:{device_id}:{stable_suffix}",
        memory_bytes=device_local_bytes,
        memory_kind=memory_kind,
        driver_version=str(properties.get("driverVersion") or "").strip() or None,
        compute_capability=None,
        backend_candidates=backend_candidates_for_vendor(vendor),
        probe_sources=("vulkaninfo-json",),
    )


def _generated_vulkan_json(directory: str) -> str:
    candidates = sorted(
        Path(directory).glob("VP_VULKANINFO_*.json"),
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    )
    for candidate in candidates:
        try:
            if candidate.stat().st_size > MAX_PROBE_OUTPUT_CHARS:
                continue
            return candidate.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
    return ""


def _probe_vulkan() -> tuple[Accelerator, ...]:
    executable = _trusted_probe_executable("vulkaninfo.exe")
    if executable is None:
        return ()
    completed = _run_probe(
        [executable, "--summary"],
        timeout=8,
        encoding="utf-8",
    )
    if completed is None:
        return ()
    if completed.returncode != 0:
        return ()
    summary = parse_vulkaninfo_summary(completed.stdout)
    detailed = list(summary)
    for index in range(len(summary)):
        json_argument = "--json" if index == 0 else f"--json={index}"
        with tempfile.TemporaryDirectory(prefix="mcasttalk-vulkan-") as probe_directory:
            detail_result = _run_probe(
                [executable, json_argument],
                timeout=12,
                encoding="utf-8",
                cwd=probe_directory,
            )
            if detail_result is None or detail_result.returncode != 0:
                continue
            parsed = parse_vulkaninfo_json(detail_result.stdout, index)
            if parsed is None:
                parsed = parse_vulkaninfo_json(_generated_vulkan_json(probe_directory), index)
            if parsed is not None:
                detailed[index] = parsed
    return tuple(detailed)


def merge_generic_accelerators(
    accelerators: Iterable[Accelerator],
) -> tuple[Accelerator, ...]:
    merged: list[Accelerator] = []
    for candidate in accelerators:
        match_index = next(
            (
                index
                for index, current in enumerate(merged)
                if current.vendor == candidate.vendor
                and current.name.casefold() == candidate.name.casefold()
                and not set(current.probe_sources).intersection(candidate.probe_sources)
            ),
            None,
        )
        if match_index is None:
            merged.append(candidate)
            continue
        current = merged[match_index]
        merged[match_index] = Accelerator(
            name=current.name,
            vendor=current.vendor,
            device_id=(
                candidate.device_id
                if "vulkaninfo-json" in candidate.probe_sources
                else current.device_id or candidate.device_id
            ),
            memory_bytes=max(current.memory_bytes, candidate.memory_bytes),
            memory_kind=(
                current.memory_kind
                if current.memory_bytes >= candidate.memory_bytes
                else candidate.memory_kind
            ),
            driver_version=current.driver_version or candidate.driver_version,
            compute_capability=current.compute_capability or candidate.compute_capability,
            backend_candidates=tuple(
                dict.fromkeys(current.backend_candidates + candidate.backend_candidates)
            ),
            probe_sources=tuple(
                dict.fromkeys(current.probe_sources + candidate.probe_sources)
            ),
        )
    return tuple(merged)


def merge_accelerators(
    generic: Iterable[Accelerator],
    nvidia: Iterable[Accelerator],
) -> tuple[Accelerator, ...]:
    merged = list(generic)
    for detailed in nvidia:
        match_index = next(
            (
                index
                for index, current in enumerate(merged)
                if current.vendor == "NVIDIA"
                and "nvidia-smi" not in current.probe_sources
                and " ".join(current.name.casefold().split())
                == " ".join(detailed.name.casefold().split())
            ),
            None,
        )
        if match_index is None:
            merged.append(detailed)
            continue
        current = merged[match_index]
        merged[match_index] = Accelerator(
            name=current.name,
            vendor="NVIDIA",
            device_id=detailed.device_id or current.device_id,
            memory_bytes=max(current.memory_bytes, detailed.memory_bytes),
            memory_kind="dedicated",
            driver_version=detailed.driver_version or current.driver_version,
            compute_capability=detailed.compute_capability,
            backend_candidates=tuple(
                dict.fromkeys(current.backend_candidates + detailed.backend_candidates)
            ),
            probe_sources=tuple(
                dict.fromkeys(current.probe_sources + detailed.probe_sources)
            ),
        )
    return tuple(merged)


def classify_tier(
    memory_bytes: int,
    accelerators: Iterable[Accelerator],
) -> tuple[str, tuple[str, ...]]:
    accelerator_list = tuple(accelerators)
    largest_reported_memory = max(
        (item.memory_bytes for item in accelerator_list),
        default=0,
    )
    notes = [
        "Tier is provisional; run the model calibration benchmark before enabling a room.",
        "Concurrency depends on active speakers, unique target languages, precision and model versions.",
    ]
    if largest_reported_memory >= 16 * GIBIBYTE and memory_bytes >= 48 * GIBIBYTE:
        return "performance", tuple(notes)
    if largest_reported_memory >= 8 * GIBIBYTE and memory_bytes >= 24 * GIBIBYTE:
        return "balanced", tuple(notes)
    if largest_reported_memory > 0:
        return "entry-accelerated", tuple(notes)
    if accelerator_list:
        return "accelerator-unmeasured", tuple(notes)
    return "cpu-only", tuple(notes)


def probe_hardware() -> HardwareProfile:
    accelerators = merge_accelerators(
        merge_generic_accelerators(
            _probe_windows_video_controllers() + _probe_vulkan()
        ),
        _probe_nvidia(),
    )
    memory = total_memory_bytes()
    tier, notes = classify_tier(memory, accelerators)
    cpu = platform.processor().strip() or os.environ.get("PROCESSOR_IDENTIFIER", "unknown")
    return HardwareProfile(
        captured_at=datetime.now(timezone.utc).isoformat(),
        os_description=platform.platform(),
        cpu=cpu,
        logical_cores=max(1, os.cpu_count() or 1),
        memory_bytes=memory,
        accelerators=accelerators,
        provisional_tier=tier,
        notes=notes,
    )

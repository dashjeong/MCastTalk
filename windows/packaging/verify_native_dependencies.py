"""Fail closed on hidden DLL dependencies in the static inference bundle.

Reads normal and delay-load PE imports. Does not execute binaries, consult PATH,
or mistake a developer machine's installed VC++ runtime for a shipped asset.
This is a packaging check, not a substitute for clean Windows execution.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path


TARGETS = ("whisper/whisper-cli.exe", "llama-cpu/llama-server.exe", "llama-vulkan/llama-server.exe")
WINDOWS_DLLS = frozenset({
    "advapi32.dll", "bcrypt.dll", "crypt32.dll", "iphlpapi.dll", "kernel32.dll",
    "msvcrt.dll", "ntdll.dll", "ole32.dll", "secur32.dll", "shell32.dll",
    "user32.dll", "userenv.dll", "ws2_32.dll",
})


def pe_imports(data: bytes) -> set[str]:
    def unpack(fmt, offset):
        if offset < 0 or offset + struct.calcsize(fmt) > len(data):
            raise ValueError("Truncated PE structure")
        return struct.unpack_from(fmt, data, offset)

    if data[:2] != b"MZ":
        raise ValueError("Missing DOS signature")
    pe, = unpack("<I", 0x3c)
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Missing PE signature")
    machine, count, _, _, _, optional_size, _ = unpack("<HHIIIHH", pe + 4)
    optional = pe + 24
    if machine != 0x8664 or unpack("<H", optional)[0] != 0x20b:
        raise ValueError("Expected x64 PE32+ runtime")
    if not 1 <= count <= 96 or optional_size < 112 + 14 * 8:
        raise ValueError("Invalid PE header size")
    directory_count, = unpack("<I", optional + 108)
    sections = []
    for index in range(count):
        virtual_size, va, size, raw = unpack("<IIII", optional + optional_size + index * 40 + 8)
        sections.append((va, virtual_size, size, raw))

    def offset(rva, length=1):
        for va, _, size, raw in sections:
            if va <= rva and rva + length <= va + size:
                result = raw + rva - va
                if result + length <= len(data):
                    return result
        raise ValueError("Import RVA outside file-backed section")

    def name(rva):
        start = offset(rva)
        end = data.find(b"\0", start, min(len(data), start + 256))
        if end < 0:
            raise ValueError("Unterminated import name")
        offset(rva, end - start + 1)
        value = data[start:end].decode("ascii").lower()
        if not value or any(c in value for c in "/\\:") or not value.endswith(".dll"):
            raise ValueError("Invalid DLL import name")
        return value

    result = set()
    for index, stride, name_index in ((1, 20, 3), (13, 32, 1)):
        if directory_count <= index:
            continue
        rva, size = unpack("<II", optional + 112 + index * 8)
        if not rva and not size:
            continue
        if not rva or size < stride:
            raise ValueError("Invalid import directory")
        terminated = False
        for step in range(min(size // stride, 4096)):
            fields = unpack("<" + "I" * (stride // 4), offset(rva + step * stride, stride))
            if not any(fields):
                terminated = True
                break
            if index == 13 and fields[0] != 1:
                raise ValueError("Unsupported non-RVA delay import")
            result.add(name(fields[name_index]))
        if not terminated:
            raise ValueError("Unterminated import directory")
    if not result:
        raise ValueError("Inference executable has no imports")
    return result


def verify(assets: Path) -> dict:
    root = assets.resolve(strict=True)
    records = []
    for relative in TARGETS:
        file = (root / relative).resolve(strict=True)
        if not file.is_relative_to(root):
            raise ValueError("Runtime path escaped asset root")
        data = file.read_bytes()
        imports = pe_imports(data)
        allowed = WINDOWS_DLLS | ({"vulkan-1.dll"} if relative.startswith("llama-vulkan/") else set())
        unsupported = imports - allowed
        if unsupported:
            raise ValueError(f"Unbundled/non-system DLL dependency in {relative}: {', '.join(sorted(unsupported))}")
        siblings = [p.name for p in file.parent.iterdir() if p.suffix.lower() == ".dll"]
        if siblings:
            raise ValueError(f"Unexpected DLLs in static runtime directory {relative}: {siblings}")
        records.append({"file": relative, "sha256": hashlib.sha256(data).hexdigest(), "imports": sorted(imports)})
    return {"passed": True, "scope": "static inference PE imports only; clean VM execution also required",
            "vulkanDriverRequirement": "vulkan-1.dll is supplied by the GPU driver; CPU fallback required when absent",
            "runtimes": records}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.assets)
    except (OSError, ValueError, struct.error) as error:
        result = {"passed": False, "error": str(error)}
    encoded = json.dumps(result, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

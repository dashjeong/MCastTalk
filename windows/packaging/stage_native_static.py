"""Assemble already-built MinGW inference runtimes with provenance and notices.

No downloads and no system runtime installation. Inputs are pinned archives;
the output contains only three inference EXEs, build evidence, and legal text.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

from verify_native_dependencies import verify

INPUTS = {
    "whisper-source.zip": "69cf2f4505458b10b88d05e094482b1827a52504d2da285dfa6b5783f261f2d1",
    "llama-source.zip": "087fbf5c7ef900a41097804f9db2213774483fd6d60801d6b63ae0d3d31ed720",
    "Vulkan-Headers.zip": "daa0ac4dc7666d6b699e9bdb096efe0a5a813a449bbb0f14c01aa303d48f2d14",
    "SPIRV-Headers.zip": "b7cbda302ef5f3d60ad443d456eaf5c610bc7ff99439d519cecd2b50d9e7d9f2",
    "w64devkit-x64-2.10.0.7z.exe": "18d0a4c71a166f8401ab6305781bec5882b40b5e06ba9807c61cb5f3b3c6325e",
    "vulkansdk-windows-X64-1.4.357.0.exe": "81f474711e9042f4cd22b31b2f7a8870db2e428b21586fb43dd80150be97310d",
}
WHISPER = "927cfce34f31707e17f2bff35c349632fb9e2c3a"
LLAMA = "b29c606e28a01b1bc8c1351026a0fa6e616bf6c4"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def cache(path):
    return dict((name, value) for name, value in re.findall(r"^([A-Z0-9_]+):[^=\n]+=(.*)$", path.read_text(), re.M))


def verify_whisper_version(executable):
    reply = subprocess.run([str(executable), "--version"], check=True, capture_output=True,
                           timeout=15, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    version = reply.stdout.decode("ascii").strip()
    if version != "whisper.cpp version: 1.9.4":
        raise ValueError("Native Whisper version does not match the worker's reviewed CLI")
    return version


def stage(root, output):
    root = root.resolve(strict=True)
    output = output.resolve()
    if output.exists():
        raise ValueError("Refusing to overwrite native runtime output")
    if not output.is_relative_to(root):
        raise ValueError("Output must be a dedicated directory under the build root")
    for name, expected in INPUTS.items():
        if digest(root / name) != expected:
            raise ValueError("Pinned input hash mismatch: " + name)
    evidence = {"schemaVersion": 1, "profile": "mingw-static-x64-avx2", "compiler": "GCC 16.2.0 / w64devkit 2.10.0",
                "sources": {"whisper.cpp": WHISPER, "llama.cpp": LLAMA},
                "inputs": INPUTS, "builds": {}, "networklessAcceptance": "required separately"}
    targets = (("whisper", "whisper-cli.exe"), ("llama-cpu", "llama-server.exe"), ("llama-vulkan", "llama-server.exe"))
    for variant, executable in targets:
        directory = root / ("build-" + variant)
        settings = cache(directory / "CMakeCache.txt")
        for name, expected in {"BUILD_SHARED_LIBS": "OFF", "GGML_STATIC": "ON", "GGML_NATIVE": "OFF",
                               "CMAKE_EXE_LINKER_FLAGS": "-static"}.items():
            if settings.get(name) != expected:
                raise ValueError(f"Unexpected {variant} build setting: {name}")
        if variant == "llama-vulkan" and settings.get("GGML_VULKAN") != "ON":
            raise ValueError("Vulkan backend must not be silently removed")
        if variant == "whisper" and settings.get("WHISPER_BUILD_IS_DEV") != "OFF":
            raise ValueError("Whisper must identify as the reviewed 1.9.4 runtime, not 1.9.4-dev")
        if variant.startswith("llama-") and any(settings.get(key) != "OFF" for key in
                                                  ("LLAMA_BUILD_UI", "LLAMA_USE_PREBUILT_UI", "LLAMA_OPENSSL")):
            raise ValueError("Disable unused llama UI provisioning and remote HTTPS support")
        file = directory / "bin" / executable
        if not file.is_file():
            raise ValueError("Build is incomplete: " + variant)
        evidence["builds"][variant] = {"sha256": digest(file), "bytes": file.stat().st_size,
            "settings": {key: value for key, value in settings.items()
                         if key.startswith(("GGML_", "LLAMA_", "WHISPER_", "BUILD_SHARED_LIBS", "CMAKE_BUILD_TYPE", "CMAKE_EXE_LINKER_FLAGS"))
                         and not any(c in value for c in ("/", "\\"))}}
    evidence["whisperVersion"] = verify_whisper_version(root / "build-whisper/bin/whisper-cli.exe")
    for variant, executable in targets:
        destination = output / variant
        destination.mkdir(parents=True)
        shutil.copy2(root / ("build-" + variant) / "bin" / executable, destination / executable)
    evidence["dependencyCheck"] = verify(output)
    notices = output / "native-licenses"
    shutil.copytree(root / "runtime-notices", notices)
    # CMake's upstream license aggregation preserves each included library's text.
    aggregation = (root / "build-llama-cpu/license.cpp").read_text(encoding="utf-8")
    licenses = re.findall(r'R"=L=\((.*?)\)=L="', aggregation, re.S)
    if len(licenses) < 3:
        raise ValueError("Missing upstream llama.cpp dependency notices")
    (notices / "LICENSE-llama-dependencies.txt").write_text("\n\n".join(licenses), encoding="utf-8")
    whisper = root / "sources" / ("whisper.cpp-" + WHISPER)
    for file, marker in (("miniaudio.h", "This software is available as a choice of the following licenses."),
                         ("stb_vorbis.c", "This software is available under 2 licenses -- choose whichever you prefer.")):
        original = (whisper / "examples" / file).read_text(encoding="utf-8")
        start = original.rfind(marker)
        if start < 0:
            raise ValueError("Missing embedded audio decoder license")
        (notices / ("LICENSE-" + file + ".txt")).write_text(original[start:], encoding="utf-8")
    for folder in ("Vulkan-Headers-1.4.357", "SPIRV-Headers-vulkan-sdk-1.4.341.0"):
        source = root / "sources" / folder
        for file in sorted(source.rglob("*")):
            if file.is_file() and (file.name.startswith(("LICENSE", "COPYING")) or "LICENSES" in file.parts):
                target = notices / folder / file.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(file, target)
    evidence["inputUrls"] = [json.loads(line) for line in (root / "source-downloads.jsonl").read_text(encoding="utf-8-sig").splitlines() if line]
    evidence["toolchainUrl"] = "https://github.com/skeeto/w64devkit/releases/download/v2.10.0/w64devkit-x64-2.10.0.7z.exe"
    evidence["shaderCompilerSource"] = json.loads((root / "sdk-evidence.json").read_text(encoding="utf-8-sig"))
    (output / "native-runtime-evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(output), "passed": True, "runtimes": 3}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    stage(args.root, args.output)

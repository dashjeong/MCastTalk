# Static Windows inference runtime profile

The first post-reboot clean Windows Sandbox run exposed a real packaging defect:
the old shared whisper.cpp executable exited with `0xC0000135`. Its imported
`VCOMP140.DLL`, `MSVCP140.dll`, and `VCRUNTIME140*.dll` were not available on
clean Windows. The shared llama.cpp binaries had the same CRT dependency risk.
Installed runtimes on the development PC had hidden it. That installer is not
accepted as a working offline release.

The replacement keeps the same Whisper and Qwen model weights, and rebuilds
whisper.cpp `927cfce34f31707e17f2bff35c349632fb9e2c3a` and llama.cpp b10964
(`b29c606e28a01b1bc8c1351026a0fa6e616bf6c4`) with w64devkit 2.10.0 / GCC 16.2.0.
The C++/GCC/OpenMP/MinGW runtimes are statically linked. No new Microsoft VC++
Redistributable package or DLL is copied into the inference bundle. Original
CPython and JVM distributions are unchanged; this is not a blanket legal
certification of every transitive component.

## Build inputs (build machine only)

Use a dedicated `source/.tools/native-static-20260922` directory. Download and
retain these original packages, verify their hashes against `INPUTS` in
`stage_native_static.py`, and extract them only inside that directory:

- w64devkit: https://github.com/skeeto/w64devkit/releases/download/v2.10.0/w64devkit-x64-2.10.0.7z.exe
- Whisper source: https://github.com/ggml-org/whisper.cpp/archive/927cfce34f31707e17f2bff35c349632fb9e2c3a.zip
- llama source: https://github.com/ggml-org/llama.cpp/archive/b29c606e28a01b1bc8c1351026a0fa6e616bf6c4.zip
- Vulkan headers: https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.4.357.zip
- SPIR-V headers: https://github.com/KhronosGroup/SPIRV-Headers/archive/refs/tags/vulkan-sdk-1.4.341.0.zip
- Signed LunarG SDK (extract `Bin/glslc.exe`, do not install):
  https://sdk.lunarg.com/sdk/download/1.4.357.0/windows/vulkansdk-windows-X64-1.4.357.0.exe
- Import definition `vulkan-1.def`:
  https://raw.githubusercontent.com/KhronosGroup/Vulkan-Loader/v1.4.357/loader/vulkan-1.def

Keep source directories under `sources/`, the toolchain under `w64devkit/`,
and the shader compiler under `vulkan-sdk/Bin/`. The SDK and compiler are not
shipped to users. Preserve original GCC `COPYING3` and `COPYING.RUNTIME` from
the `releases/gcc-16.2.0` tag as `runtime-notices/COPYING3.txt` and
`runtime-notices/COPYING.RUNTIME.txt`, plus w64devkit's
`COPYING.MinGW-w64-runtime.txt`. Record actual source URLs/hashes in
`source-downloads.jsonl`, and SDK hash/signature evidence in `sdk-evidence.json`.
Upstream source ZIPs have recorded local hashes, not an asserted independent
signature. The w64devkit download was checked against GitHub's asset digest;
the SDK's Authenticode signature was checked as Valid, LunarG, Inc.

```powershell
windows/packaging/build-native-static.ps1 `
  -NativeRoot .tools/native-static-20260922 `
  -PythonExecutable <python-3.12-executable> -Parallel 2
```

The script builds CPU Whisper, CPU llama-server, and Vulkan llama-server,
then assembles a fresh `runtimes/` directory. It refuses to overwrite that
assembled directory. Builds use Release, `GGML_NATIVE=OFF`,
`BUILD_SHARED_LIBS=OFF`, `GGML_STATIC=ON`, and `-static`.
Both `LLAMA_BUILD_UI` and `LLAMA_USE_PREBUILT_UI` are OFF so upstream's
otherwise-default build-time UI download is not attempted. MCastTalk supplies
its own bundled meeting UI and starts llama-server with `--no-webui`.
The x64 baseline is
**AVX2**. This profile does not claim compatibility with non-AVX2 CPUs.
The generated build evidence includes source revisions, compiler flags and
executable hashes. Embedded upstream dependency notices and audio decoder
licenses are retained alongside GCC, MinGW-w64 and Khronos notices.
`WHISPER_BUILD_IS_DEV=OFF` preserves the reviewed `1.9.4` version string.
The worker's exact version check stays enabled; a `1.9.4-dev` binary must not
be relabeled as accepted by weakening runtime validation.

## Acceptance gates

1. `verify_native_dependencies.py` inspects normal **and delay-load** x64 PE
   imports for all three executables. Any non-allowlisted DLL or leftover
   shared-runtime DLL aborts staging; the host's PATH is not used as evidence.
2. Vulkan may import `vulkan-1.dll` supplied by the user's GPU driver. It is
   not required for the independent CPU executable. Missing/incompatible
   Vulkan must produce the application's visible CPU fallback, not failure
   of offline CPU interpretation.
3. Recompute inference asset hashes, complete offline manifests and license
   HTML; build a fresh single-EXE candidate. Never reuse an old PASS file.
4. Install that exact EXE in a new Windows Sandbox with networking disabled
   and run installed real-model, browser, relaunch and uninstall probes.
   Static inspection, compilation and unit-test success alone are not a pass.
5. Synthetic VM media is not physical LAN, microphone, human interpretation
   quality, GPU performance, or manual installation-wizard acceptance.

No Visual Studio license entitlement is inferred from the user's machine.
No global VC++ runtime, GPU driver, host firewall or certificate trust setting
is installed or changed by this build procedure.

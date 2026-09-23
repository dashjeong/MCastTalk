# Moonshine 0.1.5 — MCastTalk ARM64 repair 1

This is the app-owned coordinate `app.guidecast.thirdparty:moonshine-voice:0.1.5-mcasttalk1`,
not an official Moonshine release. It uses upstream commit
`234f60faa0eb388b01cdf7e60aca232af37aefda`. Upstream artifact version `0.1.5` is separate
from its native C API version `30000`; the native C API version is unchanged.
The source patch, original/patched source hashes, build receipt and distribution hashes
are supplied here. This folder records dependency provenance, not app release approval.

The patch fixes the duration-confidence fade direction, bounds the VAD-disabled
resource window, clears prior-session VAD buffers on restart, releases JNI array/string
and ONNX resources, and reports native failures through Java exceptions. These resource
limits do not define semantic sentence boundaries. The app still performs that work.

The AAR retains upstream Java classes, resources and ARM64 ONNX Runtime bytes. Only
`libmoonshine.so` and `libmoonshine-jni.so` are replaced. Other ABIs are removed, matching
the app's ARM64-only ABI filter. The existing full ONNX Runtime `1.23.2` override in
`provider/moonshine-tts` remains selected in the APK; its SHA is checked separately.
No additional speech model, user audio, transcript, account data or key is included.
The two replacement libraries are already stripped; the app preserves these exact bytes.

## Reproduce and verify

Prerequisites: Python 3 (standard library only), Git, upstream source, Android NDK
`28.2.13676358`, Android CMake `3.22.1` and the original Maven AAR. The rebuild recipe
does not download dependencies or accept SDK agreements. Acquire the source at the
commit above; `source-acquisition.json` lists every required source path and SHA-256.
Resolve its two source LFS objects from their pinned `lfs_url`. Its three ONNX LFS
objects were obtained from the listed members of the pinned original AAR; do not use
LFS pointer text as native build inputs. Only the ARM64 ONNX object is linked.

Run from the repository root, replacing the uppercase input paths with local paths:

```sh
bash third_party/moonshine/0.1.5-mcasttalk1/rebuild-arm64.sh VERIFIED_SOURCE NEW_WORK_DIR NDK_DIR CMAKE_DIR
python3 scripts/moonshine-artifacts.py repack --upstream-aar ORIGINAL_AAR --native-dir NEW_WORK_DIR/artifacts/arm64-v8a
python3 scripts/moonshine-artifacts.py check-aar
python3 scripts/moonshine-artifacts.py check-apk ACTUAL_ALPHA_APK
python3 scripts/test-moonshine-artifacts.py
```

The recipe verifies all original source and patch hashes, applies the patch, verifies
the resulting changed files, builds with two parallel jobs and runs NDK
`llvm-strip --strip-unneeded` on both replacement libraries. A native compiler rebuild
is not guaranteed to be byte-identical across hosts/toolchains. A hash mismatch must
be reviewed as a new native build; the packaging command deliberately rejects it.
Do not weaken the hashes merely to make a build pass.

Given the pinned original AAR and the pinned stripped replacements, packaging sorts
entries, fixes timestamps/permissions and uses deterministic ZIP settings. Python/zlib
version differences may also change compressed bytes; the final AAR hash is mandatory.
The committed AAR removes the need for local native compilation or a patch download.
Maven resolution for its group is restricted to the repository's local Maven directory.
Its upstream transitive Java dependencies are unchanged and retain normal Gradle
resolution behavior. App runtime speech/model download policy is unchanged.

`:app:verifyMoonshineDependency` checks source patch and local AAR hashes before
`preBuild`. `:app:verifyPackagedThirdPartyLicenseAssets` checks the actual distributable
APK's two replacements, the full ONNX override, ABI inventory and all required notices.
The independent `check-apk` command also compares the packaged patch notice byte-for-byte.
These checks establish artifact identity; they do not establish transcription quality,
physical-device latency, long-session stability or release readiness.

## Licensing and maintenance

Upstream code is MIT, with separately licensed native dependencies, embedded data and
downloaded models. `LICENSE` is the complete pinned upstream agreement; `NOTICE.mcasttalk`
describes modifications. The app retains its existing MIT, Apache, BSD, MPL, BSL,
Unicode, model and voice notices in `app/src/main/assets/licenses/`. No upstream native
dependency source was modified except the seven files listed in `patch-receipt.json`.
In particular, the MPL-only Eigen subset is unchanged and available at the exact source
commit above. This local patch does not alter model permissions or imply upstream
endorsement. Local modifications follow the original files' licenses.

Maintenance is owned by this app until the changes are replaced by a reviewed upstream
release. The patch adds no network endpoint or production dependency. Artifact hashes,
scoped resolution and retaining original resource bytes reduce substitution risk;
they do not constitute a claim that the runtime is free of vulnerabilities. Native
errors and memory ownership require the actual Android regression gates before handoff.

#!/bin/bash
set -euo pipefail

# Inputs: a verified, unmodified upstream tree (receipt alongside this script),
# a new work directory, installed Android NDK r28c, and Android CMake 3.22.1.
# This script neither downloads dependencies nor accepts SDK license terms.
if [ "$#" -ne 4 ]; then
  echo "usage: rebuild-arm64.sh VERIFIED_SOURCE NEW_WORK_DIR NDK_DIR CMAKE_DIR" >&2
  exit 2
fi
source_dir="$1"
work_dir="$2"
ndk_dir="$3"
cmake_dir="$4"
artifact_dir="$(cd "$(dirname "$0")" && pwd)"
if [ -e "$work_dir" ]; then
  echo "NEW_WORK_DIR must not exist" >&2
  exit 2
fi
test -f "$source_dir/core/CMakeLists.txt"
test -x "$cmake_dir/bin/cmake"
test -f "$ndk_dir/build/cmake/android.toolchain.cmake"

python3 - "$source_dir" "$artifact_dir/patch-receipt.json" "$artifact_dir/source-acquisition.json" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
receipt_path = pathlib.Path(sys.argv[2])
receipt = json.loads(receipt_path.read_text())
patch = receipt_path.parent / "moonshine-0.1.5-mcasttalk-reliability.patch"
if hashlib.sha256(patch.read_bytes()).hexdigest() != receipt["patchSha256"]:
    raise SystemExit("source patch hash mismatch")
for item in json.loads(pathlib.Path(sys.argv[3]).read_text())["records"]:
    file = root / item["path"]
    if hashlib.sha256(file.read_bytes()).hexdigest() != item["installed_sha256"]:
        raise SystemExit("source acquisition hash mismatch: " + str(file))
for item in json.loads(pathlib.Path(sys.argv[2]).read_text())["files"]:
    expected = item["originalSha256"]
    file = root / item["path"]
    if expected is None:
        if file.exists():
            raise SystemExit("new patch path already exists: " + str(file))
    elif hashlib.sha256(file.read_bytes()).hexdigest() != expected:
        raise SystemExit("upstream source hash mismatch: " + str(file))
PY

mkdir -p "$work_dir"
cp -R "$source_dir" "$work_dir/source"
git -C "$work_dir/source" apply --check "$artifact_dir/moonshine-0.1.5-mcasttalk-reliability.patch"
git -C "$work_dir/source" apply "$artifact_dir/moonshine-0.1.5-mcasttalk-reliability.patch"

python3 - "$work_dir/source" "$artifact_dir/patch-receipt.json" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
for item in json.loads(pathlib.Path(sys.argv[2]).read_text())["files"]:
    if hashlib.sha256((root / item["path"]).read_bytes()).hexdigest() != item["patchedSha256"]:
        raise SystemExit("patched source hash mismatch: " + item["path"])
PY

"$cmake_dir/bin/cmake" -S "$work_dir/source/core" -B "$work_dir/build" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$cmake_dir/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo
"$cmake_dir/bin/cmake" --build "$work_dir/build" \
  --target moonshine moonshine-jni voice-activity-detector-test --parallel 2

strip_candidates=("$ndk_dir"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
if [ "${#strip_candidates[@]}" -ne 1 ] || [ ! -x "${strip_candidates[0]}" ]; then
  echo "Expected exactly one installed NDK llvm-strip" >&2
  exit 2
fi
mkdir -p "$work_dir/artifacts/arm64-v8a"
"${strip_candidates[0]}" --strip-unneeded \
  "$work_dir/build/libmoonshine.so" \
  -o "$work_dir/artifacts/arm64-v8a/libmoonshine.so"
"${strip_candidates[0]}" --strip-unneeded \
  "$work_dir/build/moonshine-jni/libmoonshine-jni.so" \
  -o "$work_dir/artifacts/arm64-v8a/libmoonshine-jni.so"

echo "Stripped ARM64 replacements: $work_dir/artifacts/arm64-v8a"
echo "Package input hashes and Android validation must still pass. Native byte-identical rebuild is not guaranteed."

#!/bin/sh
set -eu
# Pass the official NDK LLVM prebuilt directory. No network access or host-specific paths.
toolchain=${1:?Usage: sh scripts/build-rnnoise.sh /path/to/ndk/toolchains/llvm/prebuilt/host}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
build="$root/core/audio/build/rnnoise-native"
mkdir -p "$build" "$root/core/audio/src/main/jniLibs/arm64-v8a"
cd "$root"
test "$(shasum -a 256 third_party/rnnoise/source-v0.2.tar.gz | cut -d ' ' -f 1)" = 07e43a760a34812db9b213253c42d02579e635bdae96d445cf559ed070c5b4ca
test "$(shasum -a 256 third_party/rnnoise/model-0b50c45-runtime.tar.gz | cut -d ' ' -f 1)" = a1d04a49ac5d1a3b41590435b50e8033e23ac9783872cf2fdc22276e028f76ba
tar -xzf third_party/rnnoise/source-v0.2.tar.gz -C "$build"
tar -xzf third_party/rnnoise/model-0b50c45-runtime.tar.gz -C "$build"
cd "$build"
"$toolchain/bin/clang" --target=aarch64-linux-android29 --sysroot="$toolchain/sysroot" \
  -O2 -fPIC -shared -fvisibility=hidden -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -DRNNOISE_BUILD -DHAVE_LRINTF -DHAVE_LRINT -Iinclude -Isrc -I"$root/core/audio/src/main/cpp" \
  src/denoise.c src/rnn.c src/pitch.c src/kiss_fft.c src/celt_lpc.c \
  src/nnet.c src/nnet_default.c src/parse_lpcnet_weights.c src/rnnoise_data.c src/rnnoise_tables.c \
  "$root/core/audio/src/main/cpp/rnnoise_jni.c" -lm \
  -Wl,-z,relro,-z,now,-z,max-page-size=16384,-soname,libguidecast_rnnoise.so \
  -o "$root/core/audio/src/main/jniLibs/arm64-v8a/libguidecast_rnnoise.so"

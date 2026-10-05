#!/bin/sh
set -eu
# Official NDK r28c+, Android API30, LP64 little endian. No network or device operations.
toolchain=${1:?Usage: sh scripts/build-lame.sh /path/to/ndk/toolchains/llvm/prebuilt/host [arm64-v8a|x86_64]}
abi=${2:-arm64-v8a}
case "$abi" in arm64-v8a) target=aarch64-linux-android30;; x86_64) target=x86_64-linux-android30;; *) exit 2;; esac
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
build="$root/app/build/lame-$abi"
out="$root/app/src/main/jniLibs/$abi"
mkdir -p "$build" "$out"
cd "$root"
test "$(shasum -a 256 third_party/lame/lame-4.0.tar.gz | cut -d ' ' -f 1)" = 3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb
tar -xzf third_party/lame/lame-4.0.tar.gz -C "$build"
source="$build/lame-4.0"
# Exact encoder-only sources from upstream libmp3lame/Makefile.am. No decoder/frontend/SIMD asm.
set -- VbrTag bitstream encoder fft gain_analysis id3tag lame newmdct presets psymodel quantize quantize_pvt reservoir set_get tables takehiro util vbrquantize version mpglib_interface
for name in "$@"; do
 "$toolchain/bin/clang" --target="$target" --sysroot="$toolchain/sysroot" -O2 -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
  -ffile-prefix-map="$root"=. -fmacro-prefix-map="$root"=. -fdebug-prefix-map="$root"=. \
  -DHAVE_CONFIG_H -I"$root/third_party/lame/android" -I"$source" -I"$source/include" -I"$source/libmp3lame" \
  -c "$source/libmp3lame/$name.c" -o "$build/$name.o"
done
"$toolchain/bin/clang" --target="$target" --sysroot="$toolchain/sysroot" -shared "$build"/*.o -lm \
 -Wl,--no-undefined,-z,relro,-z,now,-z,max-page-size=16384,-soname,libmp3lame.so -o "$out/libmp3lame.so"
"$toolchain/bin/clang" --target="$target" --sysroot="$toolchain/sysroot" -O2 -fPIC -shared -fstack-protector-strong -D_FORTIFY_SOURCE=2 \
 -ffile-prefix-map="$root"=. -fmacro-prefix-map="$root"=. -fdebug-prefix-map="$root"=. \
 -I"$source/include" "$root/third_party/lame/android/mp3_jni.c" -L"$out" -lmp3lame \
 -Wl,--no-undefined,-z,relro,-z,now,-z,max-page-size=16384,-soname,libguidecast_mp3.so -o "$out/libguidecast_mp3.so"
"$toolchain/bin/llvm-strip" --strip-unneeded "$out/libmp3lame.so" "$out/libguidecast_mp3.so"

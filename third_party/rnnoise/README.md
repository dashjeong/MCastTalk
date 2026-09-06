# RNNoise 0.2 provenance and build

- Upstream: https://github.com/xiph/rnnoise (Xiph.Org), BSD-3-Clause.
- Fixed release v0.2, commit `904a876dce1f9ab8860c0a5000ed151f9f6eef58`.
- Official model: https://media.xiph.org/rnnoise/models/rnnoise_data-0b50c45.tar.gz
  SHA-256 `4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43`.
- Only runtime generated C/header retained, not training checkpoint; weights are the default
  upstream model, not the smaller/lower-capacity alternative. Runtime is entirely offline.
- Vendored source archive SHA-256 `07e43a760a34812db9b213253c42d02579e635bdae96d445cf559ed070c5b4ca`.
- Runtime C/header archive SHA-256 `a1d04a49ac5d1a3b41590435b50e8033e23ac9783872cf2fdc22276e028f76ba`.
- Android build: official NDK r27c, API29, ARM64 NEON, O2, no fast-math, RELRO/NOW,
  stack protector/FORTIFY, 16KiB ELF load alignment. See `scripts/build-rnnoise.sh`.
- NDK Darwin ZIP SHA-1 was checked against Google's repository manifest:
  `0217c10ffbec496bb9fbfbb3c6fc2477c6b77297`. Toolchain is not packaged in APK.
- JNI binary SHA-256 `46f8cd40b4ea6d37ccd18c0f897d3adcaabe9daf7a599eca6c55d80e4614469e`.
- Upstream source unchanged. Our `os_support.h` supplies the single OPUS_CLEAR macro referenced
  by 0.2's NEON header; it does not replace the RNN or import allocation hooks. JNI validates the
  fixed 480-float boundary. Instances are synchronized across process/close and do not share state.
- No new network, permissions, runtime model downloader, analytics, GPU or license activation.
  Native code still requires device validation; this audit is not a claim of vulnerability freedom.

Rebuild offline:

```sh
sh scripts/build-rnnoise.sh /path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64
```

The source archive includes COPYING. The app's License menu includes an offline copy in
`assets/licenses/RNNOISE-BSD-3-CLAUSE.txt`.

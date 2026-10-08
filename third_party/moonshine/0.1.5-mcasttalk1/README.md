# Moonshine 0.1.5 — MCastTalk ARM64 dependency

The app-owned coordinate is `app.guidecast.thirdparty:moonshine-voice:0.1.5-mcasttalk1`, based on upstream commit `234f60faa0eb388b01cdf7e60aca232af37aefda`. It is a local native correction, not an official upstream release. See [LICENSE](LICENSE), [modification notice](NOTICE.mcasttalk) and [pinned artifact provenance](provenance.json). Model weights are not included and retain their own terms.

The patch and `patch-receipt.json` provide changed source hashes; `source-acquisition.json` identifies the exact upstream source inputs. Native C API version `30000` is unchanged. The local AAR replaces ARM64 Moonshine libraries and preserves upstream Java/resources. The app uses the separately pinned full ONNX Runtime 1.23.2 from `provider/moonshine-tts`.

Optional native reproduction needs the verified source, original Maven AAR, Android NDK `28.2.13676358` and CMake `3.22.1`:

```sh
bash third_party/moonshine/0.1.5-mcasttalk1/rebuild-arm64.sh VERIFIED_SOURCE NEW_WORK_DIR NDK_DIR CMAKE_DIR
python3 scripts/moonshine-artifacts.py repack --upstream-aar ORIGINAL_AAR --native-dir NEW_WORK_DIR/artifacts/arm64-v8a
python3 scripts/moonshine-artifacts.py check-aar
python3 scripts/moonshine-artifacts.py check-apk ACTUAL_ALPHA_APK
```

The recipe does not download source or accept SDK agreements. Resolve the pinned source/LFS inputs in `source-acquisition.json` before building. Native or compressed archive rebuilds are not guaranteed to be byte-identical across toolchains/hosts. The committed AAR is sufficient for the ordinary app build. The app verifies its AAR, patch and packaged native hashes with the required license notices.

Keep all native dependency licenses and corresponding-source references in the app notices. Artifact integrity is distinct from speech accuracy, latency and operational suitability.

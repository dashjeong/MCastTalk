# Moonshine TTS native runtime provenance

`src/main/jniLibs/arm64-v8a/libonnxruntime.so` is the ARM64 library extracted
without modification from Microsoft ONNX Runtime Android 1.23.2:

- Maven coordinate: `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`
- Source URL: `https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.23.2/onnxruntime-android-1.23.2.aar`
- Downloaded AAR SHA-256: `82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827`
- Packaged ARM64 library SHA-256: `e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302`

Moonshine Voice 0.1.5 is built against ONNX Runtime 1.23.2, but its reduced
Android binary omits `QLinearMatMul(10)` while the current official Kokoro
model requires it. The full same-version runtime restores that operator. The
device integration test downloads and synthesizes every configured language
so an upstream model/runtime mismatch cannot be reported as ready.

ONNX Runtime is licensed under the MIT License.

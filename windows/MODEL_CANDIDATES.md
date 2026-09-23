# Gate 1 offline model candidates

> Evidence snapshot: 2026-09-19. Candidate status is not a product selection.
> GitHub stars are a volatile popularity signal, not a quality score. Every
> runtime and every weight file still needs a pinned revision, hash, license
> inventory, and same-machine benchmark.

## 2026-09-19 user/operator re-review

The earlier funnel below is retained as a baseline, not an exhaustive current
SOTA ranking. New challengers and the implementation priorities are detailed in
`USER_OPERATOR_BENCHMARK_2026-09-19.md`. Exact GitHub REST observations are in
`RESEARCH_SNAPSHOT_2026-09-19.json`; use those in preference to rounded older counts.

| Lane | Added challenger and primary source | Evidence and release boundary |
|---|---|---|
| STT | [Qwen3-ASR 0.6B / 1.7B](https://github.com/QwenLM/Qwen3-ASR), [1.7B card](https://huggingface.co/Qwen/Qwen3-ASR-1.7B) | Four required languages listed; Apache-2.0 card; streaming currently documented via vLLM. Windows/Radeon runtime and end-to-end latency unverified. Compare with Whisper, not an automatic replacement. |
| STT | [Cohere Transcribe 2B](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026) | Apache-2.0; 14 languages include ko/en/ja/zh; native Transformers inference documented. Gated access asks for contact-data sharing: no consent or download performed. Windows memory/streaming/runtime spike required. |
| NMT | [TranslateGemma 4B / 12B / 27B](https://huggingface.co/google/translategemma-4b-it) | 55-language translation family, model-specific template, Gemma terms rather than Apache/MIT. Gated terms not accepted; redistribution review and all 12 local translation directions required. 4B first spike; no fit or speed promise for 4GiB GPU. |
| TTS | [Qwen3-TTS CustomVoice 0.6B](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice), [1.7B](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice) | Apache-2.0 cards; [official project](https://github.com/QwenLM/Qwen3-TTS) lists ko/en/ja/zh and streaming. Compare preset voices with Melo baseline. No voice cloning, unverified Vulkan support must not be advertised. |

New model downloads, execution, model-pack admission and a final quality winner
are **not** completed by this desk review. No cloud gateway has been added:
offline execution remains the product requirement. Vulkan support belongs to a
specific runtime/model combination, not to every model merely because the GPU
supports Vulkan. `llama.cpp` is a candidate translation runtime only after exact
architecture/tokenizer/chat-template compatibility and conversion are tested.

## Candidate funnel

| Pipeline | Candidate | Popularity snapshot | License posture | ko/en/ja/zh-CN coverage | Gate 1 status |
|---|---|---:|---|---|---|
| ASR | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) with pinned OpenAI Whisper weights | ~53.7k GitHub stars | runtime MIT; weight artifact must be recorded separately | multilingual candidate | benchmark |
| ASR | [faster-whisper](https://github.com/SYSTRAN/faster-whisper) with the same pinned Whisper family | ~25.5k GitHub stars | runtime MIT; weight artifact must be recorded separately | multilingual candidate | benchmark |
| ASR | [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) | ~1.1k HF likes | CC-BY-4.0 | **fails**: its official 25-language list omits ko/ja/zh | reference only |
| NMT | [MADLAD-400 3B MT](https://huggingface.co/google/madlad400-3b-mt) | model-card candidate | Apache-2.0 | 400+ language model; verify the four required directions | benchmark after Windows runtime spike |
| NMT | [NLLB-200 distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M) | ~975 HF likes | **CC-BY-NC-4.0** | broad multilingual | blocked from product pack |
| TTS | [MeloTTS](https://github.com/myshell-ai/MeloTTS) | ~7.6k GitHub stars | code/model cards MIT; package each language checkpoint separately | official project lists Korean, English, Japanese, Chinese | primary benchmark |
| TTS | [Kokoro](https://github.com/hexgrad/kokoro) | ~8.9k GitHub stars | Apache-2.0 project; G2P dependency inventory required | does not yet prove one consistent four-language pack | secondary/per-language spike |
| TTS | [Piper](https://github.com/OHF-Voice/piper1-gpl) | ~5.6k GitHub stars | GPL-3.0 runtime; every voice has its own model card | voice-specific | legal review hold |
| TTS | [F5-TTS](https://github.com/SWivid/F5-TTS) | popular research project | code MIT, official pretrained weights **CC-BY-NC-4.0** | incomplete product-safe four-language set | blocked from product pack |

## Initial benchmark lanes

1. ASR lane A: `whisper.cpp` native Windows builds, CPU and available GPU
   backends. Lane B: `faster-whisper`/CTranslate2 on CPU INT8 and NVIDIA CUDA
   when detected. Use identical Whisper weight revisions and decoding settings.
2. NMT lane A: MADLAD-400 3B MT baseline. A smaller or quantized derivative is
   eligible only when its provenance and inherited Apache-2.0 license are
   explicit. Compare all 12 directed pairs among ko/en/ja/zh-CN, not only
   English-centric pairs.
3. TTS lane A: four separately pinned MeloTTS language checkpoints. Measure
   first-audio latency, real-time factor, peak memory, intelligibility via ASR
   round-trip, and native-speaker MOS. Do not enable voice cloning by default.

## Cross-vendor Windows execution policy

Backend availability is declared per model pack and then intersected with the
detected adapter capabilities. A detected GPU is never treated as usable until
that exact model/backend/device combination passes a warm-up, correctness,
memory, sustained-load, and p95 latency calibration.

| Hardware | Portable first | Additional candidate | Mandatory fallback |
|---|---|---|---|
| AMD Radeon | Vulkan, DirectML/Windows ML | HIP only where the pinned Windows runtime explicitly supports the card | CPU |
| Intel Arc/Iris/Xe | Vulkan, DirectML/Windows ML | OpenVINO | CPU |
| NVIDIA | Vulkan, DirectML/Windows ML | CUDA, TensorRT | CPU |
| Qualcomm/other DX12 | DirectML/Windows ML | QNN where packaged and verified | CPU |

`whisper.cpp` is the first ASR engine because its official build supports the
cross-vendor `GGML_VULKAN` path in addition to CUDA and AMD HIP. DirectML is a
broad DX12 compatibility path, but Microsoft now describes it as sustained
engineering and directs new Windows ONNX deployments toward Windows ML. The
adapter boundary therefore keeps `directml` replaceable by a future `winml`
implementation rather than coupling model code to it.

### Current Radeon 560X development machine

The local Vulkan driver identifies `Radeon Pro 560X` (`vendor 0x1002`, device
`0x67ef`) and exposes Vulkan 1.2. The Vulkan memory heaps report 4 GiB of
device-local memory plus a host-visible heap; the UI/driver may describe total
graphics memory differently. Gate 1 therefore treats this as an
`entry-accelerated` target and measures Vulkan and DirectML independently. It
does not budget models as if 8 GiB of dedicated VRAM were guaranteed.

## Required evidence

- `benchmark-evidence.schema.json` is the interchange contract.
- Each run pins candidate revision, model hash, dataset revision, settings, and
  the hardware profile hash.
- ASR reports WER for English and language-appropriate CER/WER for CJK plus
  partial/final latency and RTF.
- NMT reports chrF++ and COMET (or a documented replacement), terminology
  accuracy, hallucination/omission counts, and p50/p95 latency per direction.
- TTS reports time-to-first-audio, RTF, peak memory, native-speaker MOS,
  pronunciation error cases, and a safety review for voice identity.
- Runs on different hardware fingerprints, suites, suite versions, or required
  language sets are rejected as incomparable by the bootstrap summarizer.

## License decisions already enforced

- `CC-BY-NC`, research-only, and CPML-like markers are rejected by model-pack
  verification.
- GPL/LGPL/MPL/AGPL candidates require an explicit legal/distribution review;
  they are not silently admitted.
- Converting or quantizing a model does not change the upstream weight license.

# ChipIT

**On-device Android LLM runner, CPU/GPU hardware benchmarker, and guarded App Interaction Agent.**

> **Platform:** Android 9+ (API 28+, `arm64-v8a`) · **License:** MIT · **Distribution:** GitHub Releases (sideload APK)

---

## Key Features

- **Direct Hugging Face Hub Discovery:** Search GGUF models and `litert-community` (`.task` / `.litertlm`) models directly via the Hugging Face Hub API (`≤ 8 GB` mobile filter), with automatic **🟢 Fits / 🟡 Tight / 🔴 Won't fit** RAM badges, SHA-256 verification, optional encrypted HF token (`EncryptedSharedPreferences`) for gated models, and local SAF file import.
- **CPU & GPU Compute Selection:** Unified `InferenceEngine` abstraction supporting **CPU (ARM NEON)**, **GPU (Vulkan / Adreno OpenCL)**, **Hybrid (CPU + N GPU layers)**, and **LiteRT-LM GPU**.
- **Real-Time Hardware Telemetry & Benchmarks:** Tracks model load time, TTFT, prefill/decode tok/s, app PSS RAM, CPU %, Adreno GPU %, battery draw/temperature, and `PowerManager` thermal status. Includes a 4-prompt standard benchmark suite, CPU vs. GPU comparison chart, and CSV/JSON export via Android Share Sheet (`FileProvider`).
- **3-Tier App Interaction Agent:** Executes user requests across installed apps using **Intents & Deep Links → Android AppFunctions → AccessibilityService**, protected by per-app allow-lists, password field redaction, risky-action confirmation gates (send/pay/delete), a persistent Foreground Service Kill Switch, and an immutable Room audit log.

## Project Structure

- `app/src/main/java/com/chipit/app/model/` — Hugging Face Hub client, binary GGUF header parser, device-fit RAM calculator, `ModelManager`
- `app/src/main/java/com/chipit/app/engine/` — `InferenceEngine`, `LlamaCppEngine`, `LiteRtEngine`, `LlamaJni`
- `app/src/main/cpp/` — NDK CMake + JNI C++20 bridge (`CMakeLists.txt`, `llama_jni.cpp`)
- `app/src/main/java/com/chipit/app/metrics/` — `MetricsCollector`, `BenchmarkRunner` (CSV/JSON export)
- `app/src/main/java/com/chipit/app/agent/` — `AgentOrchestrator`, `ToolRegistry`, `ChipITAccessibilityService`, `RiskyActionClassifier`
- `app/src/main/java/com/chipit/app/data/` — Room entities/DAOs (`ChipITDatabase`) & `SettingsStore`
- `app/src/main/java/com/chipit/app/ui/` — Jetpack Compose Material 3 screens (`ModelsScreen`, `ChatAgentScreen`, `BenchmarksScreen`, `SettingsScreen`)

## Building

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

See [SPEC.md](SPEC.md) for the full product and technical specification.

## License

Source code is licensed under the [MIT License](LICENSE). Downloaded model weights remain governed by their respective upstream Hugging Face licenses.

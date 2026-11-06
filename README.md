# My Mobile Agent

Android GGUF chat app with model downloads and management, voice input/output, multiple conversations, ordered system instructions, visible agent tools and standalone hypercube RAG.

## Use

1. Install the APK from Releases. Android 8+, ARM64 or x86_64. No weights are bundled.
2. In Models choose a catalog model, browse GitHub release assets, paste a direct HTTPS GGUF URL, or import a model. Load a single-file instruct GGUF that fits your phone's RAM.
3. Enable and reorder individual System Instructions.
4. Import UTF-8 documents or add text under Knowledge. Hypercube retrieval automatically supplies relevant passages based on the current conversation. The model can also request knowledge.search.
5. Type or use Voice to fill the composer. Review then send. Speak last reads the reply; Settings enables automatic speech.

The catalog includes Spark-X2.5 1.7B and 4B, Liquid LFM2.5, Qwen and SmolLM2. Each entry shows quantization and download size. Model cards open at the publisher. Download Manager reports actual byte progress and continues outside the app.

Past conversations live locally in agent.db. Their content is never automatically injected into other chats. The model must call conversations.search and then conversations.summary to obtain an extractive summary around a matching message. Tool requests and results appear in separate conversation cards.

Hypercubes are used **only for RAG**: deterministic bipolar passage signatures and Hamming similarity. This is an independent implementation. No Q-Manifold source, engine, training loop, checkpoint format or application code is included. GGUF inference uses llama.cpp.

Voice input uses an installed Android recognizer, with offline preference; network use depends on that provider. Speech output needs an installed TTS voice. Inference and RAG are local. A foreground service keeps inference active while switching apps, subject to Android process limits. Chats and imported documents survive process death; reload the GGUF afterwards.

## Build

JDK 17, Gradle 8.11.1, SDK 35, NDK 28.0.13004108, CMake 3.31.6. Download pinned llama.cpp commit d38bea34227bbf9069d62fdc239c98ab6e05435a into vendor/llama.cpp. Run gradle :app:assembleDebug. The GitHub workflow builds, lints, tests on an emulator, verifies signing/alignment and publishes an APK, source.zip, spec.md and checksums.

# Third-party software

llama.cpp (ggml-org/llama.cpp), MIT license, pinned commit d38bea34227bbf9069d62fdc239c98ab6e05435a. Fetched into vendor/llama.cpp at build. License included in app assets/LLAMA_LICENSE.txt. https://github.com/ggml-org/llama.cpp

Hypercube.java and CubeMemory.java are independent application code used exclusively for RAG. No Q-Manifold source or project components are included.

Android supplies SQLite, Download Manager, speech recognition and text-to-speech. Catalog weights are downloaded only on user request; model licenses are available through each publisher's model card. No weights bundled.

Spark chat-template test fixture: XHToken/Spark-X2.5-1.7B tokenizer_config.json, Apache-2.0, retrieved 2026-11-06 from https://huggingface.co/XHToken/Spark-X2.5-1.7B. Used only to verify format compatibility; no model weights bundled.

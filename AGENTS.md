# Project boundaries

My Mobile Agent is an independent GGUF chat app. Q-Manifold is read-only reference material for the mathematical idea of hypercubes. Never modify that repository or copy its source, engine, application, training systems, checkpoints or components into this project. Hypercubes in this app are independently implemented and used exclusively for RAG. GGUF models handle generation.

Keep tool requests and results visible as separate conversation chunks. Do not inject past conversations automatically: the model must request history search and surrounding summaries. Preserve the model catalog, download management, composable instructions and voice input/output.

Run relevant checks and publish main releases only after build, lint, integration tests and APK verification pass.

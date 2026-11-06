Fix Spark-X2.5 thinking, answer space and tool execution.

- Request direct answers by default using the model template's thinking switch. Optional short thinking budgets and collapsed reasoning cards keep answers readable.
- Increase answer allowance from 512 to 1536 tokens by default, with adjustable answer length. Trim old conversation turns using actual prompt tokens to reserve output space.
- Parse model-native reasoning and tool formats; send real tool schemas, call IDs and tool-role results. Keep requests/results visible in separate chunks.
- Repair search promises with a required, grammar-constrained native tool request instead of accepting simulated execution.
- Validate requests, reject incomplete calls, suppress repeated calls and finish with an answer after the tool budget. Ordinary questions can use the model's own knowledge.
- Preserve independent hypercube RAG, model management, history tools, instructions and voice.

Verification includes the actual Spark template's no-think/think/native-tool/result formats, native bridge compilation, Android agent-loop regressions, GGUF smoke generation, lint, APK signatures and alignment.

Also verified locally with the real Spark-X2.5 1.7B Q4_K_M model: direct fire answer, native conversation search, tool-result response, and short-thinking arithmetic.

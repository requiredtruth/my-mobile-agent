package com.mobileagent;
/** Publisher GGUF files verified when catalog was assembled. Weights download only on user request. */
public final class ModelCatalog {
 public static final class Entry {
  public final String name,repository,revision,file,note;public final long bytes;
  Entry(String name,String repo,String rev,String file,long bytes,String note){this.name=name;repository=repo;revision=rev;this.file=file;this.bytes=bytes;this.note=note;}
  public String url(){return "https://huggingface.co/"+repository+"/resolve/"+revision+"/"+file;}
  public String card(){return "https://huggingface.co/"+repository;}
 }
 public static final Entry[] ALL={
  new Entry("Spark-X2.5 1.7B · Q4_K_M","XHToken/Spark-X2.5-1.7B-GGUF","1f7fa33b1245c14730da39e125714ad3a327901b","Spark-X2.5-1.7B-Q4_K_M.gguf",1107457856L,"Compact Spark; start with 2K–4K context."),
  new Entry("Spark-X2.5 4B · Q4_K_M","XHToken/Spark-X2.5-4B-GGUF","9826e0be84e6e6e8b9668abc91421109a1df1e2d","Spark-X2.5-4B-Q4_K_M.gguf",2600224352L,"Larger Spark model; requires more free RAM than the download size."),
  new Entry("LFM2.5 1.2B Instruct · Q4_K_M","LiquidAI/LFM2.5-1.2B-Instruct-GGUF","8ed288026e23958ad9dfa92d53ed773a8eee7125","LFM2.5-1.2B-Instruct-Q4_K_M.gguf",730895168L,"Compact instruction model."),
  new Entry("LFM2.5 1.2B Thinking · Q4_K_M","LiquidAI/LFM2.5-1.2B-Thinking-GGUF","b71945805f7d4d87dabd73e26098d44bcad6c35f","LFM2.5-1.2B-Thinking-Q4_K_M.gguf",730899072L,"Reasoning model; may spend response tokens on thinking."),
  new Entry("Qwen2.5 0.5B Instruct · Q4_K_M","Qwen/Qwen2.5-0.5B-Instruct-GGUF","9217f5db79a29953eb74d5343926648285ec7e67","qwen2.5-0.5b-instruct-q4_k_m.gguf",491400032L,"Small starter model; tool reliability may be limited."),
  new Entry("Qwen2.5 1.5B Instruct · Q4_K_M","Qwen/Qwen2.5-1.5B-Instruct-GGUF","91cad51170dc346986eccefdc2dd33a9da36ead9","qwen2.5-1.5b-instruct-q4_k_m.gguf",1117320736L,"General-purpose multilingual instruct model."),
  new Entry("Qwen3 0.6B · Q8_0","Qwen/Qwen3-0.6B-GGUF","23749fefcc72300e3a2ad315e1317431b06b590a","Qwen3-0.6B-Q8_0.gguf",639446688L,"Small thinking/chat model."),
  new Entry("SmolLM2 360M Instruct · Q8_0","HuggingFaceTB/SmolLM2-360M-Instruct-GGUF","593b5a2e04c8f3e4ee880263f93e0bd2901ad47f","smollm2-360m-instruct-q8_0.gguf",386404992L,"Very small starter model; limited agent capability."),
  new Entry("Spark-X2.5 1.7B · Q8_0","XHToken/Spark-X2.5-1.7B-GGUF","1f7fa33b1245c14730da39e125714ad3a327901b","Spark-X2.5-1.7B-Q8_0.gguf",1820112704L,"Higher precision; more RAM and storage."),
  new Entry("Spark-X2.5 4B · Q8_0","XHToken/Spark-X2.5-4B-GGUF","9826e0be84e6e6e8b9668abc91421109a1df1e2d","Spark-X2.5-4B-Q8_0.gguf",4375021152L,"Large download; suited to phones with substantial free RAM.")
 };
}

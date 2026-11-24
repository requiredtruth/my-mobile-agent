#include <jni.h>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>
#include <stdexcept>
#include <codecvt>
#include <locale>
#include <memory>
#include <algorithm>
#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "log.h"
#include <cstdio>
#include <unistd.h>
static llama_model * model=nullptr;
static llama_context * ctx=nullptr;
static common_chat_templates_ptr templates;
static std::atomic<bool> cancelled{false};
static std::string prefix_file, prefix_system, prefix_tools;
static bool prefix_thinking=false;
static std::vector<llama_token> prefix_tokens;
static std::string str(JNIEnv* e,jstring s){const jchar* p=e->GetStringChars(s,nullptr);std::u16string v((const char16_t*)p,e->GetStringLength(s));e->ReleaseStringChars(s,p);return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(v);}
static void fail(JNIEnv* e,const std::string& s){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s.c_str());}
static jbyteArray bytes(JNIEnv* e,const std::string& s){auto a=e->NewByteArray(s.size());e->SetByteArrayRegion(a,0,s.size(),(const jbyte*)s.data());return a;}
static std::vector<llama_token> tokenize(const llama_vocab* vocab,const std::string& text,bool special){int n=llama_tokenize(vocab,text.data(),text.size(),nullptr,0,special,true);if(n<0)n=-n;std::vector<llama_token> out(n);n=llama_tokenize(vocab,text.data(),text.size(),out.data(),out.size(),special,true);if(n<0)throw std::runtime_error("Tokenization failed");out.resize(n);return out;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_stop(JNIEnv*,jclass){cancelled=true;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_unload(JNIEnv*,jclass){templates.reset();prefix_file.clear();prefix_tokens.clear();if(ctx)llama_free(ctx);if(model)llama_model_free(model);ctx=nullptr;model=nullptr;}
extern "C" JNIEXPORT jstring JNICALL Java_com_mobileagent_NativeModel_loadBackend(
 JNIEnv* e,jclass c,jstring path,jint context,jint threads,jboolean preferGpu){
 Java_com_mobileagent_NativeModel_unload(e,c);
 common_log_set_verbosity_thold(LOG_LEVEL_WARN);
 auto logger=[](ggml_log_level level,const char* text,void*){
  if(level==GGML_LOG_LEVEL_WARN||level==GGML_LOG_LEVEL_ERROR)std::fputs(text,stderr);
 };
 llama_log_set(logger,nullptr);ggml_log_set(logger,nullptr);llama_backend_init();
 const std::string file=str(e,path);
 auto modelParams=llama_model_default_params();modelParams.load_mode=LLAMA_LOAD_MODE_MMAP;
 auto contextParams=llama_context_default_params();
 contextParams.n_ctx=context;contextParams.n_batch=256;contextParams.n_ubatch=128;
 contextParams.n_threads=std::max(1,(int)threads);
 contextParams.n_threads_batch=contextParams.n_threads;
 bool usingGpu=false;
 auto load=[&](const llama_model_params& mp,const llama_context_params& cp){
  model=llama_model_load_from_file(file.c_str(),mp);
  if(model)ctx=llama_init_from_model(model,cp);
  if(ctx)return true;
  Java_com_mobileagent_NativeModel_unload(e,c);
  return false;
 };
 if(preferGpu&&llama_supports_gpu_offload()){
  try{
   common_params fit;
   fit.model.path=file;
   fit.fit_params_min_ctx=context;
   fit.tensor_buft_overrides.resize(llama_max_tensor_buft_overrides());
   auto gpuModel=modelParams;
   auto gpuContext=contextParams;
   common_fit_apply(fit,gpuModel,gpuContext);
   // Keep the user's context size even if the fitter suggests shrinking it.
   gpuContext.n_ctx=context;
   if(gpuModel.n_gpu_layers!=0)usingGpu=load(gpuModel,gpuContext);
  }catch(const std::exception& ex){
   std::fprintf(stderr,"Vulkan load failed, falling back to CPU: %s\n",ex.what());
   Java_com_mobileagent_NativeModel_unload(e,c);
  }
 }
 if(!usingGpu){
  ggml_backend_dev_t noGpu[]={nullptr};
  modelParams.devices=noGpu;
  modelParams.n_gpu_layers=0;
  if(!load(modelParams,contextParams)){
   fail(e,"Cannot load GGUF. Check the model architecture, available RAM and context size.");
   return nullptr;
  }
 }
 try{
  templates=common_chat_templates_init(model,"");
 }catch(const std::exception& ex){
  Java_com_mobileagent_NativeModel_unload(e,c);
  fail(e,std::string("Chat template error: ")+ex.what());
  return nullptr;
 }
 return e->NewStringUTF(usingGpu?"Vulkan + CPU":"CPU");
}
static common_chat_templates_inputs chat_inputs(const std::string& messages,const std::string& tools,bool thinking){
 common_chat_templates_inputs input;
 input.use_jinja=true;
 input.add_generation_prompt=true;
 input.enable_thinking=thinking;
 input.reasoning_format=COMMON_REASONING_FORMAT_DEEPSEEK;
 input.chat_template_kwargs["enable_thinking"]=thinking?"true":"false";
 input.messages=common_chat_msgs_parse_oaicompat(common_json::parse(messages));
 input.tools=common_chat_tools_parse_oaicompat(common_json::parse(tools));
 return input;
}

static bool restore_prefix(const std::string& path,const std::vector<llama_token>& expected){
 if(expected.empty()||access(path.c_str(),R_OK)!=0)return false;
 size_t count=0;
 std::vector<llama_token> saved(expected.size());
 return llama_state_seq_load_file(ctx,path.c_str(),0,saved.data(),saved.size(),&count)
     && count==expected.size() && saved==expected;
}

// Render the real chat template with different first user turns. Their common token
// prefix can be prefetched without evaluating either user turn or assistant header.
extern "C" JNIEXPORT jboolean JNICALL Java_com_mobileagent_NativeModel_preparePrefix(
 JNIEnv* e,jclass,jstring file,jstring system,jstring tools,jboolean thinking,jobject cb){
 if(!ctx||!model)return JNI_FALSE;
 const auto path=str(e,file), instruction=str(e,system), tool_defs=str(e,tools);
 if(prefix_file==path&&prefix_system==instruction&&prefix_tools==tool_defs&&prefix_thinking==thinking&&access(path.c_str(),R_OK)==0)return JNI_TRUE;
 prefix_file.clear();
 prefix_tokens.clear();
 cancelled=false;
 try{
  auto vocab=llama_model_get_vocab(model);
  auto render=[&](const char* probe){
   auto messages=common_json::array();
   messages.push_back(common_json{{"role","system"},{"content",instruction}});
   messages.push_back(common_json{{"role","user"},{"content",std::string(probe)}});
   auto input=chat_inputs(messages.dump(),tool_defs,thinking);
   common_chat_session session(templates.get(),vocab,input);
   return tokenize(vocab,session.prompt().str(),true);
  };
  auto first=render("Apple");
  auto second=render("Zebra");
  auto common=std::mismatch(first.begin(),first.end(),second.begin(),second.end()).first-first.begin();
  if(common==0||common>=std::min(first.size(),second.size())||common+257>=llama_n_ctx(ctx))return JNI_FALSE;
  first.resize(common);
  auto cls=e->GetObjectClass(cb);
  auto method=e->GetMethodID(cls,"token","([B)V");
  e->DeleteLocalRef(cls);
  auto progress=[&](size_t done,bool hit){
   auto payload=bytes(e,common_json{{"prompt_tokens",done},{"prompt_total",common},{"cached_tokens",hit?common:0}}.dump());
   e->CallVoidMethod(cb,method,payload);
   e->DeleteLocalRef(payload);
   return !e->ExceptionCheck();
  };
  llama_memory_clear(llama_get_memory(ctx),true);
  if(restore_prefix(path,first)){
   if(!progress(common,true))return JNI_FALSE;
  }else{
   llama_memory_clear(llama_get_memory(ctx),true);
   if(!progress(0,false))return JNI_FALSE;
   for(size_t off=0;off<first.size()&&!cancelled;){
    int size=std::min(size_t(256),first.size()-off);
    if(llama_decode(ctx,llama_batch_get_one(first.data()+off,size)))return JNI_FALSE;
    off+=size;
    if(!progress(off,false))return JNI_FALSE;
   }
   if(cancelled)return JNI_FALSE;
   const auto tmp=path+".tmp";
   if(!llama_state_seq_save_file(ctx,tmp.c_str(),0,first.data(),first.size())
       ||std::rename(tmp.c_str(),path.c_str())!=0){
    std::remove(tmp.c_str());
    return JNI_FALSE;
   }
  }
  prefix_file=path;
  prefix_system=instruction;
  prefix_tools=tool_defs;
  prefix_thinking=thinking;
  prefix_tokens=std::move(first);
  return JNI_TRUE;
 }catch(const std::exception& ex){
  std::fprintf(stderr,"Instruction cache unavailable: %s\n",ex.what());
  return JNI_FALSE;
 }
}

// JSON snapshots contain separate content, reasoning_content and native tool_calls.
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_mobileagent_NativeModel_generate(JNIEnv* e,jclass,jstring messages,jstring tools,jint answerLimit,jint thinkLimit,jboolean requireTool,jobject cb){
 if(!ctx||!model){fail(e,"Select and load a GGUF model in Models first.");return nullptr;}
 cancelled=false;
 try{
 auto vocab=llama_model_get_vocab(model);
 auto input=chat_inputs(str(e,messages),str(e,tools),thinkLimit>0);
 if(requireTool&&!input.tools.empty())input.tool_choice=COMMON_CHAT_TOOL_CHOICE_REQUIRED;
 const int desired=std::clamp((int)answerLimit,256,4096)+std::clamp((int)thinkLimit,0,256)+32;
 std::unique_ptr<common_chat_session> session;std::vector<llama_token> tokens;int trimmed=0;
 // Trim complete old turns using the actual rendered/tokenized prompt, retaining system and latest user/tool chain.
 while(true){
  session=std::make_unique<common_chat_session>(templates.get(),vocab,input);
  tokens=tokenize(vocab,session->prompt().str(),true);
  if((int)tokens.size()+desired<=(int)llama_n_ctx(ctx))break;
  size_t next=2;while(next<input.messages.size()&&input.messages[next].role!="user")++next;
  if(next>=input.messages.size())break;
  input.messages.erase(input.messages.begin()+1,input.messages.begin()+next);++trimmed;
 }
 int available=(int)llama_n_ctx(ctx)-(int)tokens.size()-1;
 if(available<256)throw std::runtime_error("Current question, instructions and tool results leave fewer than 256 output tokens. Shorten the question/instructions or increase context in Settings and reload.");
 int limit=std::min(desired,available);
 const int effectiveThinking=std::min(std::clamp((int)thinkLimit,0,256),std::max(0,limit-288));
 auto cls=e->GetObjectClass(cb);auto method=e->GetMethodID(cls,"token","([B)V");e->DeleteLocalRef(cls);
 auto publish=[&](const std::string& json){
  auto a=bytes(e,json);e->CallVoidMethod(cb,method,a);e->DeleteLocalRef(a);
  return !e->ExceptionCheck();
 };
 llama_memory_clear(llama_get_memory(ctx),true);
 size_t cached=0;
 if(!prefix_tokens.empty()&&prefix_tokens.size()<tokens.size()
     &&std::equal(prefix_tokens.begin(),prefix_tokens.end(),tokens.begin())){
  if(restore_prefix(prefix_file,prefix_tokens))cached=prefix_tokens.size();
  else{
   llama_memory_clear(llama_get_memory(ctx),true);
   prefix_file.clear();prefix_tokens.clear();
  }
 }
 const auto prefillStarted=std::chrono::steady_clock::now();
 auto prefill=[&](size_t done){
  auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-prefillStarted).count();
  return publish(common_json{{"prompt_tokens",done},{"prompt_total",tokens.size()},{"cached_tokens",cached},{"elapsed_ms",elapsed}}.dump());
 };
 if(!prefill(cached))return nullptr;
 for(size_t off=cached;off<tokens.size()&&!cancelled;off+=256){
  int size=std::min(size_t(256),tokens.size()-off);
  if(llama_decode(ctx,llama_batch_get_one(tokens.data()+off,size)))throw std::runtime_error("Prompt evaluation failed; reduce context or model size.");
  if(!prefill(off+size))return nullptr;
 }
 if(cancelled)return bytes(e,"{\"content\":\"\",\"finish_reason\":\"stopped\"}");
 common_params_sampling sp;sp.temp=.6f;sp.top_k=40;sp.top_p=.9f;session->apply_sampling(sp);
 std::unique_ptr<common_sampler,decltype(&common_sampler_free)> sampler(common_sampler_init(model,sp),common_sampler_free);
 if(!sampler)throw std::runtime_error("Cannot initialize model sampler");
 const auto started=std::chrono::steady_clock::now();
 std::string raw,finish="length";int used=0,thinkingTokens=0;bool capped=false;
 auto start=session->thinking_start_tag();
 if(start.empty())start="<think>";
 auto ends=session->thinking_end_tags();
 if(ends.empty())ends.push_back("</think>");
 auto open_thinking=[&](const std::string& combined){
  auto opened=combined.rfind(start);
  if(opened==std::string::npos)return opened;
  for(const auto& end:ends){
   if(combined.find(end,opened+start.size())!=std::string::npos)return std::string::npos;
  }
  return opened+start.size();
 };
 auto snapshot=[&](){
  auto j=session->msg().to_json_oaicompat();
  auto combined=session->generation_prompt()+raw;
  auto opened=open_thinking(combined);
  // A parser may wait for the closing delimiter before returning a reasoning node.
  if(opened!=std::string::npos){
   size_t end=combined.size();
   for(const auto& tag:ends){
    for(size_t n=1;n<tag.size()&&n<=combined.size()-opened;++n){
     if(combined.compare(combined.size()-n,n,tag,0,n)==0)end=std::min(end,combined.size()-n);
    }
   }
   j["reasoning_content"]=combined.substr(opened,end-opened);
   j["content"]="";
   j.erase("tool_calls");
  }
  j["finish_reason"]=finish;
  j["output_tokens"]=used;
  j["output_budget"]=limit;
  j["elapsed_ms"]=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count();
  j["trimmed_turns"]=trimmed;
  j["thinking_capped"]=capped;
  return j.dump_safe();
 };
 auto feed=[&](llama_token token){
  common_sampler_accept(sampler.get(),token,true);
  std::vector<char> piece(256);int n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);if(n<0){piece.resize(-n);n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);}
  if(n>0){std::string p(piece.data(),n);raw+=p;common_chat_input chunk;chunk.append(p,token);session->feed(chunk);}
  if(llama_decode(ctx,llama_batch_get_one(&token,1)))throw std::runtime_error("Token evaluation failed");++used;
 };
 while(used<limit&&!cancelled){
  auto token=common_sampler_sample(sampler.get(),ctx,-1);
  if(llama_vocab_is_eog(vocab,token)){finish="stop";break;}
  feed(token);
  bool thinking=open_thinking(session->generation_prompt()+raw)!=std::string::npos;
  if(thinking&&++thinkingTokens>=std::max(1,effectiveThinking)){
   if(!publish(snapshot()))return nullptr;
   auto closing=tokenize(vocab,ends.front(),false);
   for(auto t:closing){if(used>=limit||cancelled)break;feed(t);}capped=true;
  }
  bool stop=false;for(auto& end:session->additional_stops())if(!end.empty()&&raw.size()>=end.size()&&raw.compare(raw.size()-end.size(),end.size(),end)==0)stop=true;
  if(stop){finish="stop";break;}
  if(!publish(snapshot()))return nullptr;
 }
 if(cancelled)finish="stopped";
 // Partial parsing is intentional on cancellation/length: never turn incomplete tool syntax into execution.
 if(finish=="stop")session->finish();
 return bytes(e,snapshot());
 }catch(const std::exception& ex){fail(e,std::string("Generation error: ")+ex.what());return nullptr;}
}

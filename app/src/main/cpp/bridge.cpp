#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <stdexcept>
#include <codecvt>
#include <locale>
#include <memory>
#include <algorithm>
#include "llama.h"
#include "chat.h"
#include "sampling.h"
static llama_model * model=nullptr;
static llama_context * ctx=nullptr;
static common_chat_templates_ptr templates;
static std::atomic<bool> cancelled{false};
static std::string str(JNIEnv* e,jstring s){const jchar* p=e->GetStringChars(s,nullptr);std::u16string v((const char16_t*)p,e->GetStringLength(s));e->ReleaseStringChars(s,p);return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(v);}
static void fail(JNIEnv* e,const std::string& s){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s.c_str());}
static jbyteArray bytes(JNIEnv* e,const std::string& s){auto a=e->NewByteArray(s.size());e->SetByteArrayRegion(a,0,s.size(),(const jbyte*)s.data());return a;}
static std::vector<llama_token> tokenize(const llama_vocab* vocab,const std::string& text,bool special){int n=llama_tokenize(vocab,text.data(),text.size(),nullptr,0,special,true);if(n<0)n=-n;std::vector<llama_token> out(n);n=llama_tokenize(vocab,text.data(),text.size(),out.data(),out.size(),special,true);if(n<0)throw std::runtime_error("Tokenization failed");out.resize(n);return out;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_stop(JNIEnv*,jclass){cancelled=true;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_unload(JNIEnv*,jclass){templates.reset();if(ctx)llama_free(ctx);if(model)llama_model_free(model);ctx=nullptr;model=nullptr;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_load(JNIEnv* e,jclass c,jstring path,jint context,jint threads){
 Java_com_mobileagent_NativeModel_unload(e,c);llama_backend_init();
 auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;
 model=llama_model_load_from_file(str(e,path).c_str(),mp);
 if(!model){fail(e,"Cannot load GGUF. Check model architecture, available RAM and whether all split files are present.");return;}
 auto cp=llama_context_default_params();cp.n_ctx=context;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=threads;cp.n_threads_batch=threads;
 ctx=llama_init_from_model(model,cp);
 if(!ctx){llama_model_free(model);model=nullptr;fail(e,"Not enough memory for this context. Reduce context size or use a smaller quantization.");return;}
 try{templates=common_chat_templates_init(model,"");}catch(const std::exception& ex){Java_com_mobileagent_NativeModel_unload(e,c);fail(e,std::string("Chat template error: ")+ex.what());}
}
// JSON snapshots contain separate content, reasoning_content and native tool_calls.
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_mobileagent_NativeModel_generate(JNIEnv* e,jclass,jstring messages,jstring tools,jint answerLimit,jint thinkLimit,jobject cb){
 if(!ctx||!model){fail(e,"Select and load a GGUF model in Models first.");return nullptr;}
 cancelled=false;
 try{
 auto vocab=llama_model_get_vocab(model);
 common_chat_templates_inputs input;input.use_jinja=true;input.add_generation_prompt=true;
 input.enable_thinking=thinkLimit>0;input.reasoning_format=COMMON_REASONING_FORMAT_DEEPSEEK;
 input.chat_template_kwargs["enable_thinking"]=thinkLimit>0?"true":"false";
 input.messages=common_chat_msgs_parse_oaicompat(common_json::parse(str(e,messages)));
 input.tools=common_chat_tools_parse_oaicompat(common_json::parse(str(e,tools)));
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
 llama_memory_clear(llama_get_memory(ctx),true);
 for(size_t off=0;off<tokens.size()&&!cancelled;off+=256){int size=std::min(size_t(256),tokens.size()-off);if(llama_decode(ctx,llama_batch_get_one(tokens.data()+off,size)))throw std::runtime_error("Prompt evaluation failed; reduce context or model size.");}
 if(cancelled)return bytes(e,"{\"content\":\"\",\"finish_reason\":\"stopped\"}");
 common_params_sampling sp;sp.temp=.6f;sp.top_k=40;sp.top_p=.9f;session->apply_sampling(sp);
 std::unique_ptr<common_sampler,decltype(&common_sampler_free)> sampler(common_sampler_init(model,sp),common_sampler_free);
 if(!sampler)throw std::runtime_error("Cannot initialize model sampler");
 auto cls=e->GetObjectClass(cb);auto method=e->GetMethodID(cls,"token","([B)V");e->DeleteLocalRef(cls);
 std::string raw,finish="length";int used=0,thinkingTokens=0;bool capped=false;
 auto snapshot=[&](){auto j=session->msg().to_json_oaicompat();j["finish_reason"]=finish;j["output_tokens"]=used;j["output_budget"]=limit;j["trimmed_turns"]=trimmed;j["thinking_capped"]=capped;return j.dump();};
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
  std::string combined=session->generation_prompt()+raw;
  auto start=session->thinking_start_tag();if(start.empty())start="<think>";
  auto ends=session->thinking_end_tags();if(ends.empty())ends.push_back("</think>");
  size_t opened=combined.rfind(start),closed=std::string::npos;
  for(auto& end:ends){auto pos=combined.rfind(end);if(pos!=std::string::npos&&(closed==std::string::npos||pos>closed))closed=pos;}
  bool thinking=opened!=std::string::npos&&(closed==std::string::npos||opened>closed);
  if(thinking&&++thinkingTokens>=std::max(1,(int)thinkLimit)){
   auto closing=tokenize(vocab,ends.front(),false);
   for(auto t:closing){if(used>=limit||cancelled)break;feed(t);}capped=true;
  }
  bool stop=false;for(auto& end:session->additional_stops())if(!end.empty()&&raw.size()>=end.size()&&raw.compare(raw.size()-end.size(),end.size(),end)==0)stop=true;
  if(stop){finish="stop";break;}
  auto a=bytes(e,snapshot());e->CallVoidMethod(cb,method,a);e->DeleteLocalRef(a);if(e->ExceptionCheck())return nullptr;
 }
 if(cancelled)finish="stopped";
 // Partial parsing is intentional on cancellation/length: never turn incomplete tool syntax into execution.
 if(finish=="stop")session->finish();
 return bytes(e,snapshot());
 }catch(const std::exception& ex){fail(e,std::string("Generation error: ")+ex.what());return nullptr;}
}

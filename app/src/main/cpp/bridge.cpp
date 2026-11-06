#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <stdexcept>
#include <codecvt>
#include <locale>
#include "llama.h"
static llama_model * model=nullptr;
static llama_context * ctx=nullptr;
static std::atomic<bool> cancelled{false};
static std::string str(JNIEnv* e,jstring s){const jchar* p=e->GetStringChars(s,nullptr);std::u16string v((const char16_t*)p,e->GetStringLength(s));e->ReleaseStringChars(s,p);return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(v);}
static void fail(JNIEnv* e,const std::string& s){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s.c_str());}
static jbyteArray bytes(JNIEnv* e,const std::string& s){auto a=e->NewByteArray(s.size());e->SetByteArrayRegion(a,0,s.size(),(const jbyte*)s.data());return a;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_stop(JNIEnv*,jclass){cancelled=true;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_unload(JNIEnv*,jclass){if(ctx)llama_free(ctx);if(model)llama_model_free(model);ctx=nullptr;model=nullptr;}
extern "C" JNIEXPORT void JNICALL Java_com_mobileagent_NativeModel_load(JNIEnv* e,jclass c,jstring path,jint context,jint threads){
 Java_com_mobileagent_NativeModel_unload(e,c);llama_backend_init();
 auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.use_mmap=true;
 model=llama_model_load_from_file(str(e,path).c_str(),mp);
 if(!model){fail(e,"Cannot load GGUF. Check model architecture, available RAM and whether all split files are present.");return;}
 auto cp=llama_context_default_params();cp.n_ctx=context;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=threads;cp.n_threads_batch=threads;
 ctx=llama_init_from_model(model,cp);
 if(!ctx){llama_model_free(model);model=nullptr;fail(e,"Not enough memory for this context. Reduce context size or use a smaller quantization.");}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_mobileagent_NativeModel_generate(JNIEnv* e,jclass,jobjectArray roles,jobjectArray texts,jint limit,jobject cb){
 if(!ctx||!model){fail(e,"Select and load a GGUF model in Models first.");return nullptr;}
 cancelled=false;
 std::vector<std::string> rs,ts;
 int count=e->GetArrayLength(roles);rs.reserve(count);ts.reserve(count);
 for(int i=0;i<count;i++){auto r=(jstring)e->GetObjectArrayElement(roles,i);auto t=(jstring)e->GetObjectArrayElement(texts,i);rs.push_back(str(e,r));ts.push_back(str(e,t));e->DeleteLocalRef(r);e->DeleteLocalRef(t);}
 std::vector<llama_chat_message> msgs;for(int i=0;i<count;i++)msgs.push_back({rs[i].c_str(),ts[i].c_str()});
 const char* tmpl=llama_model_chat_template(model,nullptr);if(!tmpl)tmpl="chatml";
 int n=llama_chat_apply_template(tmpl,msgs.data(),msgs.size(),true,nullptr,0);
 if(n<0){fail(e,"This model uses an unsupported chat template. Choose an instruct GGUF with a supported template.");return nullptr;}
 std::vector<char> prompt(n+1);llama_chat_apply_template(tmpl,msgs.data(),msgs.size(),true,prompt.data(),prompt.size());
 auto vocab=llama_model_get_vocab(model);int nt=llama_tokenize(vocab,prompt.data(),n,nullptr,0,true,true);if(nt<0)nt=-nt;
 std::vector<llama_token> tokens(nt);nt=llama_tokenize(vocab,prompt.data(),n,tokens.data(),tokens.size(),true,true);
 if(nt<=0||nt+limit>(int)llama_n_ctx(ctx)){fail(e,"Conversation exceeds the selected model context. Start a new chat or increase context in Settings.");return nullptr;}
 llama_memory_clear(llama_get_memory(ctx),true);
 for(int off=0;off<nt&&!cancelled;off+=256){int size=std::min(256,nt-off);if(llama_decode(ctx,llama_batch_get_one(tokens.data()+off,size))){fail(e,"Prompt evaluation failed; reduce the context or model size.");return nullptr;}}
 auto sampler=llama_sampler_chain_init(llama_sampler_chain_default_params());
 llama_sampler_chain_add(sampler,llama_sampler_init_top_k(40));llama_sampler_chain_add(sampler,llama_sampler_init_top_p(.9f,1));llama_sampler_chain_add(sampler,llama_sampler_init_temp(.65f));llama_sampler_chain_add(sampler,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
 auto method=e->GetMethodID(e->GetObjectClass(cb),"token","([B)V");std::string output;
 for(int i=0;i<limit&&!cancelled;i++){
  auto token=llama_sampler_sample(sampler,ctx,-1);if(llama_vocab_is_eog(vocab,token))break;
  std::vector<char> piece(256);int len=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);if(len<0){piece.resize(-len);len=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);}
  if(len>0){std::string p(piece.data(),len);output+=p;auto a=bytes(e,p);e->CallVoidMethod(cb,method,a);e->DeleteLocalRef(a);if(e->ExceptionCheck())break;}
  if(llama_decode(ctx,llama_batch_get_one(&token,1))){fail(e,"Token evaluation failed.");break;}
 }
 llama_sampler_free(sampler);return bytes(e,output);
}

#include "chat.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
static void check(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
int main(int argc,char** argv){
 try{
  check(argc==2,"template path required");std::ifstream file(argv[1]);std::string tmpl((std::istreambuf_iterator<char>(file)),{});
  auto templates=common_chat_templates_init(nullptr,tmpl,"<｜start▁of▁sentence｜>","<｜end▁of▁sentence｜>");
  common_chat_templates_inputs in;in.enable_thinking=false;in.reasoning_format=COMMON_REASONING_FORMAT_DEEPSEEK;in.chat_template_kwargs["enable_thinking"]="false";
  in.messages=common_chat_msgs_parse_oaicompat(common_json::parse(R"([{"role":"system","content":"Answer directly."},{"role":"user","content":"What is fire?"}])"));
  in.tools=common_chat_tools_parse_oaicompat(common_json::parse(R"([{"type":"function","function":{"name":"knowledge.search","description":"Search passages","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}])"));
  common_chat_session direct(templates.get(),nullptr,in);
  check(direct.prompt().str().find("<|Bot|></think>")!=std::string::npos,"Spark no-think prefill missing");
  direct.finish(common_chat_input("Fire is combustion."));check(direct.msg().content=="Fire is combustion.","final answer parsed incorrectly");check(direct.msg().reasoning_content.empty(),"direct answer became reasoning");
  in.enable_thinking=true;in.chat_template_kwargs["enable_thinking"]="true";
  common_chat_session thinking(templates.get(),nullptr,in);
  check(thinking.prompt().str().find("<|Bot|><think>")!=std::string::npos,"Spark think prefill missing");
  thinking.finish(common_chat_input("Brief reasoning.</think>Fire is combustion."));
  check(thinking.msg().reasoning_content=="Brief reasoning.","implicit opening think tag not parsed");check(thinking.msg().content=="Fire is combustion.","reasoning leaked into answer");
  in.enable_thinking=false;in.chat_template_kwargs["enable_thinking"]="false";
  in.tool_choice=COMMON_CHAT_TOOL_CHOICE_REQUIRED;
  common_chat_session tools(templates.get(),nullptr,in);common_params_sampling sampling;tools.apply_sampling(sampling);check(!sampling.grammar_lazy&&!sampling.grammar.grammar.empty(),"required native tool grammar missing");
  tools.finish(common_chat_input("<tool_call>knowledge.search<arg_key>query</arg_key><arg_value>fire</arg_value></tool_call>"));
  check(tools.msg().tool_calls.size()==1,"Spark native tool request not recognized");auto call=tools.msg().tool_calls.front();check(call.name=="knowledge.search","wrong tool name");check(common_json::parse(call.arguments)["query"]=="fire","wrong native argument");
  auto assistant=tools.msg();assistant.tool_calls[0].id="call1";in.messages.push_back(assistant);common_chat_msg result;result.role="tool";result.tool_name="knowledge.search";result.tool_call_id="call1";result.content="[Untrusted tool result] Fire is combustion.";in.messages.push_back(result);
  common_chat_session after(templates.get(),nullptr,in);check(after.prompt().str().find("<tool_response>")!=std::string::npos,"native tool result format missing");
  in.tool_choice=COMMON_CHAT_TOOL_CHOICE_AUTO;common_chat_session unicode(templates.get(),nullptr,in);
  unicode.feed(common_chat_input("\xf0\x9f"));auto partial=unicode.msg().to_json_oaicompat().dump_safe();check(common_json::parse(partial).is_object(),"partial UTF-8 snapshot is invalid JSON");
  unicode.finish(common_chat_input("\x94\xa5"));check(unicode.msg().content=="\xf0\x9f\x94\xa5","split emoji was not preserved");
  std::cout<<"Spark no-think, implicit reasoning, native tools, result round-trip and split UTF-8 passed\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}

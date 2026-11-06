package com.mobileagent;
import org.json.*;

/** Only complete, final-channel tool requests may be executed. */
public final class Protocol {
 public static String answer(String raw){
  String s=raw;int end=s.lastIndexOf("</think>");if(end>=0)s=s.substring(end+8);
  int start=s.indexOf("<think>");if(start>=0)s=s.substring(0,start);
  // Do not expose incomplete reasoning/tool tags while streaming.
  int tool=s.indexOf("<tool_call>");if(tool>=0)s=s.substring(0,tool);
  for(String tag:new String[]{"<think>","</think>","<tool_call>"})for(int n=1;n<tag.length();n++)if(s.endsWith(tag.substring(0,n)))s=s.substring(0,s.length()-n);
  return s.trim();
 }
 public static JSONObject call(String text)throws JSONException{
  String s=answer(text);if(s.startsWith("```"))s=s.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
  if(!s.startsWith("{"))return null;
  try{JSONTokener tok=new JSONTokener(s);Object value=tok.nextValue();if(tok.nextClean()!=0||!(value instanceof JSONObject))return null;JSONObject v=(JSONObject)value;
   if(v.has("tool")&&v.opt("arguments") instanceof JSONObject)return v;
  }catch(JSONException ignored){}return null;
 }
 public static boolean promisesTool(String text){
  String s=text.toLowerCase(java.util.Locale.ROOT);
  return s.matches("(?s).*(?:i'll|i will|let me|i have|i've|i did|i used|i searched|i found).*(?:search|searched|searching|conversations\\.search|knowledge\\.search).*")||s.matches("(?s).*(?:conversations\\.search|knowledge\\.search|conversations\\.summary).*(?:tool|result|found|search).*" );
 }
 public static JSONObject message(String role,String content)throws JSONException{return new JSONObject().put("role",role).put("content",content);}
 public static JSONArray tools()throws JSONException{
  JSONArray all=new JSONArray();
  for(String name:new String[]{"knowledge.search","conversations.search","conversations.summary"}){
   JSONObject props=new JSONObject();JSONArray required=new JSONArray();
   if(name.endsWith("summary")){props.put("conversation",new JSONObject().put("type","string"));props.put("message",new JSONObject().put("type","integer"));required.put("conversation").put("message");}
   else{props.put("query",new JSONObject().put("type","string"));required.put("query");}
   String description=name.startsWith("knowledge")?"Search imported knowledge passages by relevant words.":name.endsWith("summary")?"Read an extractive summary around a conversation/message search hit.":"Search other conversations for matching excerpts and IDs; request summary for surrounding context.";
   all.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",props).put("required",required).put("additionalProperties",false))));
  }return all;
 }
 public static void validate(String name,JSONObject args)throws JSONException{
  if(!name.equals("knowledge.search")&&!name.equals("conversations.search")&&!name.equals("conversations.summary"))throw new JSONException("Unknown tool: "+name);
  if(name.endsWith("summary")){if(!(args.opt("conversation") instanceof String)||args.getString("conversation").trim().isEmpty()||!(args.opt("message") instanceof Number)||args.getLong("message")<1)throw new JSONException("summary requires a conversation ID and positive message ID");}
  else if(!(args.opt("query") instanceof String)||args.getString("query").trim().isEmpty())throw new JSONException("search requires a nonempty query");
 }
}

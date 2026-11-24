package com.mobileagent;
import org.junit.*;
import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
public class IntegrationTest {
 Context context;Store db;
 @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.deleteDatabase("agent.db");db=new Store(context);Agent.store=db;Agent.stopped=false;Agent.memory=new CubeMemory(db);Agent.memory.changed();}
 @After public void close(){Agent.listener=null;Agent.generator=NativeModel::generate;Agent.loaded="";Agent.memory.close();db.close();Agent.store=null;Agent.memory=null;}
 @Test public void conversationRetrievalIsRequestOnly()throws Exception{
  String old=db.newChat();db.message(old,"user","The lighthouse access code is violet.");db.message(old,"assistant","Remembered the lighthouse note.");db.message(old,"user","The nearby dock opens at noon.");String current=db.newChat();db.message(current,"user","New private thread");
  assertEquals("[]",db.searchChats("absent",current));JSONArray hits=new JSONArray(db.searchChats("lighthouse",current));assertTrue(hits.length()>0);
  JSONObject hit=hits.getJSONObject(0);String summary=db.summary(hit.getString("conversation"),hit.getLong("message"));assertTrue(summary.contains("dock"));assertFalse(summary.contains("private thread"));assertEquals("[]",Agent.memory.search("lighthouse"));
  assertFalse(db.instructions().contains("lighthouse"));assertTrue(Agent.TOOLS.contains("NEVER supplied automatically"));
 }
 @Test public void cubeRagAndPersistence()throws Exception{
  Agent.memory.add("Astronomy manual","The lunar telescope uses a silver mirror to collect moonlight. A telescope reveals craters.");
  String result=Agent.memory.search("lunar telescope moonlight");assertTrue(result.contains("Astronomy manual"));assertTrue(result.contains("silver mirror"));Agent.memory.close();Agent.memory=new CubeMemory(db);assertTrue(Agent.memory.search("moonlight").contains("silver mirror"));
 }
 @Test public void toolParsingAndInstructionOrder()throws Exception{
  assertNull(Agent.call("A normal answer."));assertEquals("knowledge.search",Agent.call("<think>Need evidence.</think>{\"tool\":\"knowledge.search\",\"arguments\":{}}").getString("tool"));assertEquals("knowledge.search",Agent.call("```json\n{\"tool\":\"knowledge.search\",\"arguments\":{\"query\":\"lunar\"}}\n```").getString("tool"));
  db.getWritableDatabase().execSQL("INSERT INTO instructions(title,text,enabled,position) VALUES('later','Later rule',1,10)");db.getWritableDatabase().execSQL("INSERT INTO instructions(title,text,enabled,position) VALUES('off','Disabled secret',0,-1)");assertTrue(db.instructions().indexOf("helpful")<db.instructions().indexOf("Later rule"));assertFalse(db.instructions().contains("Disabled secret"));
 }
 @Test public void streamingSpeechQueuesEachSentenceOnce(){
  SentenceStream speech=new SentenceStream();
  List<String> queued=new ArrayList<>();
  speech.append("First sentence",false,queued::add);
  assertTrue(queued.isEmpty());
  speech.append("First sentence. Second",false,queued::add);
  assertTrue(queued.isEmpty());
  speech.append("First sentence. Second sentence. Final",false,queued::add);
  assertEquals(Collections.singletonList("First sentence. Second sentence."),queued);
  speech.append("First sentence. Second sentence. Final",true,queued::add);
  speech.append("First sentence. Second sentence. Final",true,queued::add);
  assertEquals(Arrays.asList("First sentence. Second sentence.","Final"),queued);

  SentenceStream oneSentence=new SentenceStream();
  List<String> single=new ArrayList<>();
  oneSentence.append("Only one sentence.",false,single::add);
  assertTrue(single.isEmpty());
  oneSentence.append("Only one sentence.",true,single::add);
  oneSentence.append("Only one sentence.",true,single::add);
  assertEquals(Collections.singletonList("Only one sentence."),single);
 }
 @Test public void realGgufNativeInference()throws Exception{
  File model=new File(context.getFilesDir(),"smoke.gguf");try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("smoke.gguf");OutputStream out=new FileOutputStream(model)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
  Models.validate(model);NativeModel.load(model.getAbsolutePath(),2048,2);byte[][] last={null};List<Integer> prefill=new ArrayList<>();byte[] result=NativeModel.generate("[{\"role\":\"system\",\"content\":\"Tell stories.\"},{\"role\":\"user\",\"content\":\"Once upon a time, a little cat\"}]","[]",256,0,false,b->{
   last[0]=b;
   try{
    JSONObject update=new JSONObject(new String(b,StandardCharsets.UTF_8));
    if(update.has("prompt_total")){
     int count=update.getInt("prompt_tokens");
     if(count==0||count==update.getInt("prompt_total"))prefill.add(count);
    }
   }catch(JSONException e){throw new AssertionError(e);}
  });assertEquals(2,prefill.size());assertEquals(0,(int)prefill.get(0));assertTrue(prefill.get(1)>0);
  JSONObject response=new JSONObject(new String(result,StandardCharsets.UTF_8));assertFalse(response.optString("content").isEmpty());assertNotNull(last[0]);assertTrue(response.getInt("output_tokens")>0);
  File cache=new File(context.getCacheDir(),"smoke-instructions.kv");
  cache.delete();
  assertTrue(NativeModel.preparePrefix(cache.getPath(),"Tell stories.","[]",false,true,b->{}));
  assertTrue(cache.length()>0);
  assertCachedPrompt("Tell stories.","Once upon a time, a little fox",true);
  NativeModel.unload();
  NativeModel.load(model.getAbsolutePath(),2048,2);
  int[] restored={0};
  assertTrue(NativeModel.preparePrefix(cache.getPath(),"Tell stories.","[]",false,true,b->restored[0]=cachedTokens(b)));
  assertTrue(restored[0]>0);
  assertCachedPrompt("Tell stories.","Once upon a time, a little dog",true);
  assertCachedPrompt("Tell stories.\nContinue with the answer.","Once upon a time, a little dog",true);
  assertCachedPrompt("Write recipes.","Once upon a time, a little dog",false);
  cache.delete();
  File inline=new File(context.getCacheDir(),"smoke-inline-instructions.kv");
  inline.delete();
  assertFalse(NativeModel.preparePrefix(inline.getPath(),"Describe animals.","[]",false,false,
    b->{throw new AssertionError("Configuration must not prefill separately");}));
  assertCachedPrompt("Describe animals.","Once upon a time, a little fox",false);
  assertTrue(inline.length()>0);
  assertCachedPrompt("Describe animals.","Once upon a time, a little dog",true);
  inline.delete();
  JSONArray history=new JSONArray().put(Protocol.message("system","Tell stories."));
  for(int i=0;i<60;i++){history.put(Protocol.message("user","The little cat walked through the garden with a friend and saw a bright flower. "+i));history.put(Protocol.message("assistant","The friend smiled and the cat sat on the grass beside the tree."));}
  history.put(Protocol.message("user","Once upon a time, a little cat"));
  JSONObject trimmed=new JSONObject(new String(NativeModel.generate(history.toString(),"[]",256,0,false,b->{}),StandardCharsets.UTF_8));assertTrue(trimmed.getInt("trimmed_turns")>0);assertFalse(trimmed.optString("content").isEmpty());
  JSONObject stopped=new JSONObject(new String(NativeModel.generate("[{\"role\":\"user\",\"content\":\"Once upon a time\"}]","[]",256,0,false,b->NativeModel.stop()),StandardCharsets.UTF_8));assertEquals("stopped",stopped.getString("finish_reason"));
  NativeModel.unload();model.delete();
 }
 private static int cachedTokens(byte[] update){
  try{return new JSONObject(new String(update,StandardCharsets.UTF_8)).optInt("cached_tokens");}
  catch(JSONException e){throw new AssertionError(e);}
 }
 private static void assertCachedPrompt(String instructions,String user,boolean expected)throws Exception{
  JSONArray messages=new JSONArray().put(Protocol.message("system",instructions)).put(Protocol.message("user",user));
  int[] cached={0};
  NativeModel.generate(messages.toString(),"[]",256,0,false,b->cached[0]=Math.max(cached[0],cachedTokens(b)));
  assertEquals(expected,cached[0]>0);
 }
 @Test public void thinkingAndStrictToolBoundaries()throws Exception{
  assertEquals("Fire is combustion.",Protocol.answer("<think>plan</think>Fire is combustion."));
  assertEquals("",Protocol.answer("<think>unfinished secret"));
  assertEquals("Answer",Protocol.answer("Answer<th"));
  assertNull(Agent.call("<think>{\"tool\":\"knowledge.search\",\"arguments\":{\"query\":\"fire\"}}"));
  assertNull(Agent.call("{\"tool\":\"knowledge.search\",\"arguments\":{}} trailing"));
  assertNull(Agent.call("Here is an example {\"tool\":\"knowledge.search\"}"));
  try{Protocol.validate("knowledge.search",new JSONObject().put("query",""));fail();}catch(JSONException expected){}
  try{Protocol.validate("unknown",new JSONObject());fail();}catch(JSONException expected){}
 }
 @Test public void nativeToolRoundTripThenFinalAnswer()throws Exception{
  String chat=db.newChat();db.message(chat,"user","Find lighthouse in past notes");Agent.loaded="test";int[] runs={0};
  List<String> spokenThinking=new ArrayList<>();
  Agent.listener=new Agent.Listener(){
   @Override public void update(String id,String live){}
   @Override public void thinkingSpeech(String id,long round,String text,boolean finished){
    if(!finished)spokenThinking.add(text);
   }
  };
  Agent.generator=(messages,tools,answer,thinking,required,callback)->{try{
   assertEquals(1536,answer);JSONArray input=new JSONArray(messages);JSONObject output;
   if(runs[0]++==0){assertEquals(3,new JSONArray(tools).length());
    callback.token(new JSONObject().put("reasoning_content","Need past notes").toString().getBytes(StandardCharsets.UTF_8));
    assertEquals("Need past notes",Agent.liveThinking);
    assertEquals("",Agent.live);
    assertEquals(Collections.singletonList("Need past notes"),spokenThinking);
    output=new JSONObject("{\"content\":\"\",\"finish_reason\":\"stop\",\"reasoning_content\":\"Need past notes\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"conversations.search\",\"arguments\":\"{\\\"query\\\":\\\"lighthouse\\\"}\"}}]}");}
   else{JSONObject result=input.getJSONObject(input.length()-1);assertEquals("tool",result.getString("role"));assertEquals("call1",result.getString("tool_call_id"));assertTrue(result.getString("content").contains("Untrusted"));output=new JSONObject().put("content","No past notes matched.").put("finish_reason","stop");}
   byte[] bytes=output.toString().getBytes(StandardCharsets.UTF_8);callback.token(bytes);return bytes;
  }catch(JSONException e){throw new AssertionError(e);}};
  Agent.run(chat,"Find lighthouse in past notes");assertEquals(2,runs[0]);List<String[]> rows=db.messages(chat);assertTrue(rows.stream().anyMatch(m->m[1].equals("reasoning")));assertEquals("No past notes matched.",rows.get(rows.size()-1)[2]);assertEquals(2,rows.stream().filter(m->m[1].equals("tool")).count());
 }
 @Test public void visibleReplySurvivesAnIncompleteToolMarker(){
  String chat=db.newChat();db.message(chat,"user","Who are you?");Agent.loaded="test";
  String visible="I am a local assistant. I can answer questions. I run on your device.";
  List<String> drafts=new ArrayList<>(), spoken=new ArrayList<>();
  Agent.listener=new Agent.Listener(){
   @Override public void update(String id,String live){drafts.add(live);}
   @Override public void speech(String id,long round,String text,boolean finished){spoken.add(text);}
  };
  int[] runs={0};
  Agent.generator=(messages,tools,answer,thinking,required,callback)->{
   runs[0]++;
   callback.token(new JSONObject(Collections.singletonMap("content",visible)).toString().getBytes(StandardCharsets.UTF_8));
   return new JSONObject(Collections.singletonMap("content",visible+"<tool_call>")).toString().getBytes(StandardCharsets.UTF_8);
  };
  Agent.run(chat,"Who are you?");
  assertEquals(1,runs[0]);
  assertTrue(drafts.contains(visible));
  assertEquals(Arrays.asList(visible,visible),spoken);
  assertEquals(visible,db.messages(chat).get(1)[2]);
 }
 @Test public void truncatedToolIsNeverExecuted()throws Exception{
  String chat=db.newChat();db.message(chat,"user","What is fire?");Agent.loaded="test";int[] runs={0};
  Agent.generator=(messages,tools,answer,thinking,required,callback)->{String result=runs[0]++==0?"{\"content\":\"{\\\"tool\\\":\\\"knowledge.search\\\",\\\"arguments\\\":{\\\"query\\\":\\\"fire\\\"}}\",\"finish_reason\":\"length\"}":"{\"content\":\"Fire is combustion.\",\"finish_reason\":\"stop\"}";return result.getBytes(StandardCharsets.UTF_8);};
  Agent.run(chat,"What is fire?");assertEquals(2,runs[0]);assertFalse(db.messages(chat).stream().anyMatch(m->m[1].equals("tool")));assertEquals("Fire is combustion.",db.messages(chat).get(db.messages(chat).size()-1)[2]);
 }

 @Test public void promisedSearchRequiresRealToolRetry()throws Exception{
  String chat=db.newChat();db.message(chat,"user","Search past conversations for lighthouse");Agent.loaded="test";int[] runs={0};
  Agent.generator=(messages,tools,answer,thinking,required,callback)->{try{
   if(runs[0]++==0){assertFalse(required);return new JSONObject().put("content","I'll search your past conversations now.").put("finish_reason","stop").toString().getBytes(StandardCharsets.UTF_8);}
   assertTrue(required);return new JSONObject().put("content","Cannot complete the search.").put("finish_reason","stop").toString().getBytes(StandardCharsets.UTF_8);
  }catch(JSONException e){throw new AssertionError(e);}};
  Agent.run(chat,"Search past conversations for lighthouse");assertEquals(2,runs[0]);assertFalse(db.messages(chat).stream().anyMatch(m->m[2].contains("I'll search")));
 }

}

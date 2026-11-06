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
 @After public void close(){Agent.generator=NativeModel::generate;Agent.loaded="";Agent.memory.close();db.close();Agent.store=null;Agent.memory=null;}
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
 @Test public void realGgufNativeInference()throws Exception{
  File model=new File(context.getFilesDir(),"smoke.gguf");try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("smoke.gguf");OutputStream out=new FileOutputStream(model)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
  Models.validate(model);NativeModel.load(model.getAbsolutePath(),2048,2);byte[][] last={null};byte[] result=NativeModel.generate("[{\"role\":\"system\",\"content\":\"Tell stories.\"},{\"role\":\"user\",\"content\":\"Once upon a time, a little cat\"}]","[]",256,0,b->last[0]=b);JSONObject response=new JSONObject(new String(result,StandardCharsets.UTF_8));assertFalse(response.optString("content").isEmpty());assertNotNull(last[0]);assertTrue(response.getInt("output_tokens")>0);NativeModel.unload();model.delete();
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
  Agent.generator=(messages,tools,answer,thinking,callback)->{try{
   assertEquals(1536,answer);JSONArray input=new JSONArray(messages);JSONObject output;
   if(runs[0]++==0){assertEquals(3,new JSONArray(tools).length());output=new JSONObject("{\"content\":\"\",\"finish_reason\":\"stop\",\"reasoning_content\":\"Need past notes\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"conversations.search\",\"arguments\":\"{\\\"query\\\":\\\"lighthouse\\\"}\"}}]}");}
   else{JSONObject result=input.getJSONObject(input.length()-1);assertEquals("tool",result.getString("role"));assertEquals("call1",result.getString("tool_call_id"));assertTrue(result.getString("content").contains("Untrusted"));output=new JSONObject().put("content","No past notes matched.").put("finish_reason","stop");}
   byte[] bytes=output.toString().getBytes(StandardCharsets.UTF_8);callback.token(bytes);return bytes;
  }catch(JSONException e){throw new AssertionError(e);}};
  Agent.run(chat,"Find lighthouse in past notes");assertEquals(2,runs[0]);List<String[]> rows=db.messages(chat);assertTrue(rows.stream().anyMatch(m->m[1].equals("reasoning")));assertEquals("No past notes matched.",rows.get(rows.size()-1)[2]);assertEquals(2,rows.stream().filter(m->m[1].equals("tool")).count());
 }
 @Test public void truncatedToolIsNeverExecuted()throws Exception{
  String chat=db.newChat();db.message(chat,"user","What is fire?");Agent.loaded="test";int[] runs={0};
  Agent.generator=(messages,tools,answer,thinking,callback)->{String result=runs[0]++==0?"{\"content\":\"{\\\"tool\\\":\\\"knowledge.search\\\",\\\"arguments\\\":{\\\"query\\\":\\\"fire\\\"}}\",\"finish_reason\":\"length\"}":"{\"content\":\"Fire is combustion.\",\"finish_reason\":\"stop\"}";return result.getBytes(StandardCharsets.UTF_8);};
  Agent.run(chat,"What is fire?");assertEquals(2,runs[0]);assertFalse(db.messages(chat).stream().anyMatch(m->m[1].equals("tool")));assertEquals("Fire is combustion.",db.messages(chat).get(db.messages(chat).size()-1)[2]);
 }

}

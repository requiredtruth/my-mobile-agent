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
 @After public void close(){Agent.memory.close();db.close();Agent.store=null;Agent.memory=null;}
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
  assertNull(Agent.call("A normal answer."));assertEquals("knowledge.search",Agent.call("```json\n{\"tool\":\"knowledge.search\",\"arguments\":{\"query\":\"lunar\"}}\n```").getString("tool"));
  db.getWritableDatabase().execSQL("INSERT INTO instructions(title,text,enabled,position) VALUES('later','Later rule',1,10)");db.getWritableDatabase().execSQL("INSERT INTO instructions(title,text,enabled,position) VALUES('off','Disabled secret',0,-1)");assertTrue(db.instructions().indexOf("helpful")<db.instructions().indexOf("Later rule"));assertFalse(db.instructions().contains("Disabled secret"));
 }
 @Test public void realGgufNativeInference()throws Exception{
  File model=new File(context.getFilesDir(),"smoke.gguf");try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("smoke.gguf");OutputStream out=new FileOutputStream(model)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
  Models.validate(model);NativeModel.load(model.getAbsolutePath(),2048,2);ByteArrayOutputStream stream=new ByteArrayOutputStream();byte[] result=NativeModel.generate(new String[]{"system","user"},new String[]{"Tell stories.","Once upon a time, a little cat"},32,b->stream.write(b,0,b.length));assertTrue(result.length>0);assertArrayEquals(result,stream.toByteArray());assertFalse(new String(result,StandardCharsets.UTF_8).isEmpty());NativeModel.unload();model.delete();
 }
}

package com.mobileagent;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

/** SQLite transactions preserve chats, instruction fragments and source passages independently of model memory. */
public final class Store extends SQLiteOpenHelper {
 public Store(Context c){super(c,"agent.db",null,1);getWritableDatabase().enableWriteAheadLogging();}
 public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY,title TEXT,created INTEGER)");d.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat TEXT,role TEXT,text TEXT,created INTEGER)");d.execSQL("CREATE INDEX message_chat ON messages(chat,id)");d.execSQL("CREATE TABLE instructions(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,text TEXT,enabled INTEGER,position INTEGER)");d.execSQL("CREATE TABLE passages(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT,text TEXT)");ContentValues v=new ContentValues();v.put("title","Assistant");v.put("text","You are a helpful personal assistant. Answer clearly, use available tools when needed, and distinguish source evidence from assumptions.");v.put("enabled",1);v.put("position",0);d.insertOrThrow("instructions",null,v);}
 public void onUpgrade(SQLiteDatabase d,int a,int b){}
 public String newChat(){String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("title","New conversation");v.put("created",System.currentTimeMillis());getWritableDatabase().insertOrThrow("chats",null,v);return id;}
 public void message(String chat,String role,String text){ContentValues v=new ContentValues();v.put("chat",chat);v.put("role",role);v.put("text",text);v.put("created",System.currentTimeMillis());getWritableDatabase().insertOrThrow("messages",null,v);if(role.equals("user")){getWritableDatabase().execSQL("UPDATE chats SET title=? WHERE id=? AND title='New conversation'",new Object[]{text.substring(0,Math.min(55,text.length())),chat});}}
 public List<String[]> rows(String sql,String...args){List<String[]> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(sql,args)){while(c.moveToNext()){String[] r=new String[c.getColumnCount()];for(int i=0;i<r.length;i++)r[i]=c.getString(i);out.add(r);}}return out;}
 public List<String[]> messages(String chat){return rows("SELECT id,role,text FROM messages WHERE chat=? ORDER BY id",chat);}
 public List<String[]> chats(){return rows("SELECT id,title FROM chats ORDER BY created DESC");}
 public String instructions(){StringBuilder out=new StringBuilder();for(String[] r:rows("SELECT title,text FROM instructions WHERE enabled=1 ORDER BY position,id"))out.append("\n[Instruction: ").append(r[0]).append("]\n").append(r[1]).append('\n');return out.toString();}
 public void deleteChat(String id){SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{d.delete("messages","chat=?",new String[]{id});d.delete("chats","id=?",new String[]{id});d.setTransactionSuccessful();}finally{d.endTransaction();}}
 public String searchChats(String q,String current)throws JSONException{
  if(q.trim().isEmpty())return "[]";JSONArray out=new JSONArray();String match="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  for(String[] r:rows("SELECT m.chat,c.title,m.id,m.text FROM messages m JOIN chats c ON c.id=m.chat WHERE m.chat<>? AND m.role IN ('user','assistant') AND m.text LIKE ? ESCAPE '\\' ORDER BY m.id DESC LIMIT 12",current,match))out.put(new JSONObject().put("conversation",r[0]).put("title",r[1]).put("message",Long.parseLong(r[2])).put("excerpt",r[3].substring(0,Math.min(180,r[3].length()))));
  return out.toString();
 }
 /** Only the explicit summary tool reads neighboring messages. Deterministic extractive summaries preserve provenance. */
 public String summary(String chat,long anchor)throws JSONException{
  if(rows("SELECT id FROM messages WHERE chat=? AND id=?",chat,Long.toString(anchor)).isEmpty())return "No matching conversation/message.";
  List<String[]> around=rows("SELECT id,role,text FROM (SELECT id,role,text FROM messages WHERE chat=? AND id<=? AND role IN ('user','assistant') ORDER BY id DESC LIMIT 4) ORDER BY id",chat,Long.toString(anchor));
  around.addAll(rows("SELECT id,role,text FROM messages WHERE chat=? AND id>? AND role IN ('user','assistant') ORDER BY id LIMIT 3",chat,Long.toString(anchor)));
  JSONArray out=new JSONArray();for(String[] r:around)out.put(new JSONObject().put("message",r[0]).put("role",r[1]).put("summary",r[2].substring(0,Math.min(650,r[2].length()))));return out.toString();
 }
}

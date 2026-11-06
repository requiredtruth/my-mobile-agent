package com.mobileagent;
import java.util.*;
import java.io.*;
import org.json.*;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;

/** Standalone RAG hypercubes are paged in groups of 64 passages; original source text remains in SQLite. */
public final class CubeMemory implements AutoCloseable {
 private final Store store;private final File folder;
 private final LinkedHashMap<String,Map<String,long[]>> cache=new LinkedHashMap<>(4,.75f,true);
 private volatile String info="Cubes load on demand · 4,096 dimensions";
 public CubeMemory(Store s){store=s;folder=new File(new File(s.getReadableDatabase().getPath()).getParentFile(),"knowledge-cubes");folder.mkdirs();}
 public synchronized void changed(){cache.clear();File[] files=folder.listFiles();if(files!=null)for(File f:files)f.delete();info="Knowledge updated · RAG cubes prepare on next search";}
 private Map<String,long[]> group(long group)throws IOException{
  List<String[]> passages=store.rows("SELECT id,text FROM passages WHERE id>=? AND id<? ORDER BY id",Long.toString(group*64),Long.toString((group+1)*64));
  String key=group+"-"+passages.size()+"-"+passages.get(passages.size()-1)[0];Map<String,long[]> signatures=cache.get(key);if(signatures!=null)return signatures;
  while(cache.size()>=2)cache.remove(cache.keySet().iterator().next());
  AtomicFile snapshot=new AtomicFile(new File(folder,key+".ragcube"));
  if(snapshot.getBaseFile().exists())try(DataInputStream in=new DataInputStream(new BufferedInputStream(snapshot.openRead()))){
   if(in.readInt()!=0x4D524147||in.readInt()!=1)throw new IOException("Invalid RAG cache");int count=in.readInt();if(count!=passages.size())throw new IOException("Stale RAG cache");signatures=new LinkedHashMap<>();for(int i=0;i<count;i++)signatures.put(in.readUTF(),Hypercube.read(in));
  }catch(IOException ignored){snapshot.delete();signatures=null;}
  if(signatures==null){signatures=new LinkedHashMap<>();for(String[] passage:passages){if(Agent.stopped)throw new java.util.concurrent.CancellationException("Stopped RAG preparation");signatures.put(passage[0],Hypercube.encode(passage[1]));}
   FileOutputStream raw=null;try{raw=snapshot.startWrite();DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw));out.writeInt(0x4D524147);out.writeInt(1);out.writeInt(signatures.size());for(Map.Entry<String,long[]> entry:signatures.entrySet()){out.writeUTF(entry.getKey());Hypercube.write(out,entry.getValue());}out.flush();snapshot.finishWrite(raw);}catch(IOException failure){if(raw!=null)snapshot.failWrite(raw);throw failure;}}
  cache.put(key,signatures);return signatures;
 }
 public synchronized String search(String q)throws JSONException,IOException{
  List<String[]> groups=store.rows("SELECT DISTINCT CAST(id/64 AS INTEGER) FROM passages ORDER BY 1");LinkedHashMap<String,Double> ids=new LinkedHashMap<>();int scanned=0;long[] query=Hypercube.encode(q);if(query==null)return "[]";
  for(String[] g:groups){if(Agent.stopped)throw new java.util.concurrent.CancellationException("Stopped knowledge search");info="Searching cube group "+(++scanned)+" / "+groups.size();Agent.status=info;Agent.emit();for(Map.Entry<String,long[]> entry:group(Long.parseLong(g[0])).entrySet()){double score=Hypercube.similarity(query,entry.getValue());if(score>=.055)ids.put(entry.getKey(),score);}}

  List<Map.Entry<String,Double>> ranked=new ArrayList<>(ids.entrySet());ranked.sort((a,b)->Double.compare(b.getValue(),a.getValue()));JSONArray out=new JSONArray();for(Map.Entry<String,Double> id:ranked){for(String[] p:store.rows("SELECT source,text FROM passages WHERE id=?",id.getKey()))out.put(new JSONObject().put("passage",id.getKey()).put("source",p[0]).put("cube_score",id.getValue()).put("text",p[1]));if(out.length()>=4)break;}
  info=groups.size()+" cube groups · 4,096 dimensions · cached on disk";return out.toString();
 }
 public synchronized int add(String source,String text){SQLiteDatabase db=store.getWritableDatabase();int n=0;db.beginTransaction();try{for(int off=0;off<text.length();off+=900){String p=text.substring(off,Math.min(text.length(),off+1100)).trim();if(p.isEmpty())continue;android.content.ContentValues v=new android.content.ContentValues();v.put("source",source);v.put("text",p);db.insertOrThrow("passages",null,v);n++;}db.setTransactionSuccessful();}finally{db.endTransaction();}changed();return n;}
 public String status(){return info;}
 public synchronized void close(){cache.clear();}
}

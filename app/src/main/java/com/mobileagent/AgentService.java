package com.mobileagent;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.File;
import java.util.concurrent.*;
public final class AgentService extends Service {
 public static final ExecutorService WORK=Executors.newSingleThreadExecutor();
 public void onCreate(){super.onCreate();if(Agent.store==null){Agent.store=new Store(this);Agent.memory=new CubeMemory(Agent.store);}NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("agent","Local model activity",NotificationManager.IMPORTANCE_LOW));}
 public int onStartCommand(Intent i,int flags,int id){if(i==null){stopSelf();return START_NOT_STICKY;}if("stop".equals(i.getAction())){Agent.stopped=true;NativeModel.stop();return START_NOT_STICKY;}if(Agent.busy)return START_NOT_STICKY;
  startForeground(1,new Notification.Builder(this,"agent").setSmallIcon(com.mobileagent.R.drawable.ic_agent).setContentTitle("My Mobile Agent").setContentText("Running your local model").setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).addAction(new Notification.Action.Builder(null,"Stop",PendingIntent.getService(this,1,new Intent(this,AgentService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)).build()).build());
  if("stop".equals(i.getAction())){Agent.stopped=true;NativeModel.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
  Agent.busy=true;Agent.stopped=false;String action=i.getAction();String chat=i.getStringExtra("chat");
  if(chat!=null)Agent.activeChat=chat;
  Agent.stage("load".equals(action)?"Loading model":"Starting reply");
  WORK.execute(()->{
   PowerManager.WakeLock lock=getSystemService(PowerManager.class).newWakeLock(
     PowerManager.PARTIAL_WAKE_LOCK,"mobileagent:inference");
   lock.acquire(30*60*1000L);
   try{
    SharedPreferences settings=getSharedPreferences("settings",0);
    if("load".equals(action)){
     load(new File(i.getStringExtra("path")),settings);
     Agent.stage(Agent.stopped?"Stopped":"Ready");
    }else{
     String question=i.getStringExtra("question");
     Agent.store.message(chat,"user",question);
     if(Agent.loaded.isEmpty()){
      String path=i.getStringExtra("path");
      if(path==null)path=settings.getString("model","");
      load(new File(path),settings);
     }
     if(!Agent.stopped){
      Agent.answerTokens=settings.getInt("answerTokens",1536);
      Agent.thinkingTokens=settings.getInt("thinkingTokens",0);
      Agent.run(chat,question);
     }else Agent.stage("Stopped");
    }
   }catch(Throwable e){
    Agent.stage("Error · "+e.getMessage());
    if(chat!=null)Agent.store.message(chat,"error",e.getMessage()==null?e.toString():e.getMessage());
   }finally{
    if(lock.isHeld())lock.release();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    Agent.busy=false;
    Agent.emit();
   }
  });
  return START_NOT_STICKY;
 }
 private void load(File file,SharedPreferences settings)throws Exception{
  Models.validate(file);
  Agent.loaded="";
  Agent.instructionCache=null;
  boolean preferGpu=settings.getBoolean("gpu",true);
  int context=settings.getInt("context",4096);
  Agent.stage(preferGpu?"Loading model · checking GPU":"Loading model on CPU");
  Agent.backend=NativeModel.loadBackend(file.getAbsolutePath(),context,
    Runtime.getRuntime().availableProcessors(),preferGpu);
  Agent.loaded=file.getName();
  settings.edit().putString("model",file.getAbsolutePath()).apply();
  Agent.instructionCache=new InstructionCache(this,file,Agent.backend,context);
  Agent.preparePrefix(Agent.store.instructions()+"\n"+Agent.TOOLS,settings.getInt("thinkingTokens",0)>0);
 }
 public void onTimeout(int startId,int fgsType){Agent.stopped=true;NativeModel.stop();stopSelf();}
 public IBinder onBind(Intent i){return null;}
}

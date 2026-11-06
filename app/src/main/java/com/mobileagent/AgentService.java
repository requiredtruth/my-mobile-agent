package com.mobileagent;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;
public final class AgentService extends Service {
 public static final ExecutorService WORK=Executors.newSingleThreadExecutor();
 public void onCreate(){super.onCreate();if(Agent.store==null){Agent.store=new Store(this);Agent.memory=new CubeMemory(Agent.store);}NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("agent","Local model activity",NotificationManager.IMPORTANCE_LOW));}
 public int onStartCommand(Intent i,int flags,int id){if(i==null){stopSelf();return START_NOT_STICKY;}if("stop".equals(i.getAction())){Agent.stopped=true;NativeModel.stop();return START_NOT_STICKY;}if(Agent.busy)return START_NOT_STICKY;
  startForeground(1,new Notification.Builder(this,"agent").setSmallIcon(com.mobileagent.R.drawable.ic_agent).setContentTitle("My Mobile Agent").setContentText("Running your local model").setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).addAction(new Notification.Action.Builder(null,"Stop",PendingIntent.getService(this,1,new Intent(this,AgentService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)).build()).build());
  if("stop".equals(i.getAction())){Agent.stopped=true;NativeModel.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
  Agent.busy=true;String action=i.getAction();String chat=i.getStringExtra("chat");
  WORK.execute(()->{PowerManager.WakeLock lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"mobileagent:inference");lock.acquire(30*60*1000L);try{if("load".equals(action)){Agent.status="Loading model";Agent.emit();java.io.File f=new java.io.File(i.getStringExtra("path"));Models.validate(f);Agent.loaded="";NativeModel.load(f.getAbsolutePath(),getSharedPreferences("settings",0).getInt("context",4096),Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)));Agent.loaded=f.getName();getSharedPreferences("settings",0).edit().putString("model",f.getAbsolutePath()).apply();}else Agent.run(chat,i.getStringExtra("question"));}catch(Throwable e){Agent.status="Error: "+e.getMessage();}finally{Agent.busy=false;if(lock.isHeld())lock.release();if(!Agent.status.startsWith("Error"))Agent.status="Ready";Agent.emit();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});return START_NOT_STICKY;
 }
 public void onTimeout(int startId,int fgsType){Agent.stopped=true;NativeModel.stop();stopSelf();}
 public IBinder onBind(Intent i){return null;}
}

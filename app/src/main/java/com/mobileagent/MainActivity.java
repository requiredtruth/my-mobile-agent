package com.mobileagent;
import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements Agent.Listener {
 private static final int EXPORT_GGUF=13;
 private String queuedVoice, queuedChat;
 private String chatDraft="";
 private boolean restoreChecked;
 private TextView cacheStatus;
 private String cacheSystem;
 static final int BG=0xff10191e,CARD=0xff1b2930,ACCENT=0xff7be5c4,TEXT=0xffedf5f7,MUTED=0xffa9bbc3;
 final Handler handler=new Handler(Looper.getMainLooper());final ExecutorService io=Executors.newSingleThreadExecutor();
 LinearLayout root,body,liveCards,thinkingCard,replyCard,emptyChat;TextView status,stream,thinkingStream;ProgressBar progress;Button stopOrNew;int renderedMessages;ScrollView scroll;EditText composer;Models models;String page="Chat",chat,pendingExport;TextToSpeech tts;boolean ttsReady=false,resumed=false;long speechRound=-1,utteranceId=0;boolean mutedReply=false;SentenceStream sentences=new SentenceStream(),thinkingSentences=new SentenceStream();android.content.SharedPreferences settings;
 final Runnable statusTick=new Runnable(){public void run(){
  if(!resumed)return;
  if(Agent.busy)updateStatus();
  handler.postDelayed(this,250);
 }};
 final Runnable poll=new Runnable(){public void run(){if(resumed&&page.equals("Models"))render();if(resumed)handler.postDelayed(this,1500);}};
 public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);settings=getSharedPreferences("settings",0);pendingExport=state==null?null:state.getString("pendingExport");queuedVoice=state==null?null:state.getString("queuedVoice");queuedChat=state==null?null:state.getString("queuedChat");models=new Models(this);if(Agent.store==null){Agent.store=new Store(this);Agent.memory=new CubeMemory(Agent.store);}chat=state==null?settings.getString("chat",null):state.getString("chat");if(chat==null||Agent.store.rows("SELECT id FROM chats WHERE id=?",chat).isEmpty())chat=Agent.store.newChat();tts=new TextToSpeech(this,result->{ttsReady=result==TextToSpeech.SUCCESS;});render();if(state!=null)composer.setText(state.getString("draft",""));if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},5);}
 public void onResume(){super.onResume();resumed=true;Agent.listener=this;render();if(!dispatchVoice())restoreModel();handler.post(poll);handler.post(statusTick);}
 public void onPause(){super.onPause();resumed=false;handler.removeCallbacks(poll);handler.removeCallbacks(statusTick);settings.edit().putString("chat",chat).apply();}
 public void onSaveInstanceState(Bundle state){state.putString("chat",chat);state.putString("pendingExport",pendingExport);state.putString("queuedVoice",queuedVoice);state.putString("queuedChat",queuedChat);state.putString("draft",composer==null?chatDraft:composer.getText().toString());super.onSaveInstanceState(state);}
 public void onDestroy(){if(Agent.listener==this)Agent.listener=null;if(tts!=null){tts.stop();tts.shutdown();}io.shutdown();super.onDestroy();}
 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
 LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
 TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(5),dp(6),dp(5),dp(6));return t;}
 void label(LinearLayout box,String s){box.addView(text(s,14,MUTED));}
 void title(LinearLayout box,String s){TextView t=text(s,23,TEXT);t.setTypeface(null,Typeface.BOLD);box.addView(t);}
 EditText input(String hint,String value,boolean multi){EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setHint(hint);e.setText(value);e.setTextSize(16);e.setSingleLine(!multi);e.setPadding(dp(10),dp(8),dp(10),dp(8));return e;}
 Button button(String name,Runnable action){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextColor(ACCENT);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(v->{try{action.run();}catch(Exception e){error(e);}});return b;}
 LinearLayout card(LinearLayout box){LinearLayout c=column();c.setPadding(dp(10),dp(7),dp(10),dp(7));GradientDrawable d=new GradientDrawable();d.setColor(CARD);d.setCornerRadius(dp(14));c.setBackground(d);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));box.addView(c,p);return c;}
 void error(Throwable e){new AlertDialog.Builder(this).setTitle("My Mobile Agent").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("OK",null).show();}
 void confirm(String msg,Runnable r){new AlertDialog.Builder(this).setMessage(msg).setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->r.run()).show();}
 void async(Runnable task){io.execute(()->{try{task.run();}catch(Throwable e){runOnUiThread(()->error(e));}});}
 void render(){
  int previousScroll=scroll==null?0:scroll.getScrollY();if(composer!=null)chatDraft=composer.getText().toString();composer=null;root=column();root.setBackgroundColor(BG);root.setPadding(dp(12),dp(8),dp(12),dp(6));setContentView(root);
  root.setOnApplyWindowInsetsListener((v,in)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(12),bars.top+dp(8),dp(12),bars.bottom+dp(6));}else{v.setPadding(dp(12),in.getSystemWindowInsetTop()+dp(8),dp(12),in.getSystemWindowInsetBottom()+dp(6));}return in;});
  LinearLayout head=row();TextView brand=text("My Mobile Agent",22,TEXT);brand.setTypeface(null,Typeface.BOLD);head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));head.addView(button("Chats",this::chooseChat));root.addView(head);
  status=text("",12,MUTED);status.setMaxLines(3);progress=null;cacheStatus=null;
  HorizontalScrollView nav=new HorizontalScrollView(this);LinearLayout tabs=row();for(String name:new String[]{"Chat","Models","Knowledge","Instructions","Settings"})tabs.addView(button(name,()->{page=name;render();}));nav.addView(tabs);root.addView(nav);if(!page.equals("Chat"))root.addView(status);
  scroll=new ScrollView(this);body=column();scroll.setFillViewport(true);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  switch(page){case "Chat":drawChat(chatDraft);break;case "Models":drawModels();break;case "Knowledge":drawMemory();break;case "Instructions":drawInstructions();break;default:drawSettings();}
  updateStatus();
  if(!page.equals("Chat"))scroll.post(()->scroll.scrollTo(0,previousScroll));
 }
 void drawChat(String draft){
  List<String[]> messages=Agent.store.messages(chat);
  renderedMessages=messages.size();
  emptyChat=null;
  if(messages.isEmpty()){
   emptyChat=column();
   title(emptyChat,"Your models. Your assistant.");
   label(emptyChat,"Download an instruct GGUF in Models. Add your knowledge and instructions, then type or speak. Conversations stay on this device.");
   body.addView(emptyChat);
  }
  for(String[] m:messages)messageCard(m[1],m[2]);
  liveCards=column();
  body.addView(liveCards);
  thinkingCard=card(liveCards);
  thinkingStream=thinkingDetails(thinkingCard,"",true);
  replyCard=card(liveCards);
  stream=messageText(replyCard,"assistant","");
  updateStreams(chat.equals(Agent.activeChat)?Agent.live:"",chat.equals(Agent.activeChat)?Agent.liveThinking:"");
  LinearLayout controls=row();
  LinearLayout voice=row();
  voice.addView(button("Voice",this::listen));
  CheckBox autoSend=new CheckBox(this);
  autoSend.setText("Auto-send voice");
  autoSend.setTextColor(TEXT);
  autoSend.setTextSize(13);
  autoSend.setChecked(settings.getBoolean("autoSendVoice",true));
  autoSend.setOnCheckedChangeListener((button,enabled)->settings.edit().putBoolean("autoSendVoice",enabled).apply());
  voice.addView(autoSend,new LinearLayout.LayoutParams(0,-2,1));
  CheckBox speakThinking=new CheckBox(this);
  speakThinking.setText("Speak thinking");
  speakThinking.setTextColor(TEXT);
  speakThinking.setTextSize(13);
  speakThinking.setChecked(settings.getBoolean("speakThinking",true));
  speakThinking.setOnCheckedChangeListener((button,enabled)->settings.edit().putBoolean("speakThinking",enabled).apply());
  voice.addView(speakThinking,new LinearLayout.LayoutParams(0,-2,1));
  root.addView(voice);
  stopOrNew=button(Agent.busy?"Stop":"New",()->{
   if(Agent.busy){silence();Agent.stopped=true;NativeModel.stop();Agent.stage("Stopping");}
   else{silence();chat=Agent.store.newChat();render();}
  });
  controls.addView(stopOrNew);
  controls.addView(button("Speak last",this::speakLast));
  controls.addView(button("Silence",this::silence));
  root.addView(controls);
  LinearLayout compose=row();composer=input("Message your local agent",draft,true);composer.setMaxLines(5);compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));compose.addView(button("Send",this::send));root.addView(compose);
  status.setLines(2);
  root.addView(status);
  progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
  progress.setContentDescription("Current phase progress");
  progress.setMax(100);
  root.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
  followChatBottom(scroll,body);
 }
 private void followChatBottom(ScrollView viewport,LinearLayout messages){
  Runnable bottom=()->{
   if(scroll==viewport)viewport.scrollTo(0,Math.max(0,messages.getHeight()-viewport.getHeight()));
  };
  messages.addOnLayoutChangeListener((view,left,top,right,bottomEdge,oldLeft,oldTop,oldRight,oldBottom)->{
   if(bottomEdge-top>oldBottom-oldTop)viewport.post(bottom);
  });
  viewport.addOnLayoutChangeListener((view,left,top,right,bottomEdge,oldLeft,oldTop,oldRight,oldBottom)->{
   if(bottomEdge-top!=oldBottom-oldTop)viewport.post(bottom);
  });
 }
 void messageCard(String role,String value){
  LinearLayout container=card(body);
  if(role.equals("reasoning"))thinkingDetails(container,value,false);
  else messageText(container,role,value);
 }
 private TextView messageText(LinearLayout container,String role,String value){
  boolean tool=role.equals("tool");
  String title=tool?"TOOL · local execution":role.equals("user")?"YOU":role.equals("error")?"ERROR":"ASSISTANT";
  container.addView(text(title,11,tool?ACCENT:MUTED));
  TextView content=text(value,tool?13:16,TEXT);
  content.setTextIsSelectable(true);
  if(tool)content.setTypeface(Typeface.MONOSPACE);
  container.addView(content);
  return content;
 }
 TextView thinkingDetails(LinearLayout card,String value,boolean expanded){
  TextView details=text(value,13,MUTED);
  details.setTextIsSelectable(true);
  details.setVisibility(expanded?View.VISIBLE:View.GONE);
  card.addView(button("Thinking · show / hide",()->details.setVisibility(details.getVisibility()==View.GONE?View.VISIBLE:View.GONE)));
  card.addView(details);
  return details;
 }
 void updateStreams(String reply,String thinking){
  if(thinkingCard.getVisibility()==View.GONE&&!thinking.isEmpty())thinkingStream.setVisibility(View.VISIBLE);
  if(!android.text.TextUtils.equals(thinkingStream.getText(),thinking))thinkingStream.setText(thinking);
  thinkingCard.setVisibility(thinking.isEmpty()?View.GONE:View.VISIBLE);
  if(!android.text.TextUtils.equals(stream.getText(),reply))stream.setText(reply);
  replyCard.setVisibility(reply.isEmpty()?View.GONE:View.VISIBLE);
 }
 private File lastModel(){
  String path=settings.getString("model","");
  if(path.isEmpty())return null;
  File model=new File(path);
  return model.isFile()?model:null;
 }
 private void restoreModel(){
  if(restoreChecked)return;
  restoreChecked=true;
  File model=lastModel();
  if(!Agent.busy&&Agent.loaded.isEmpty()&&model!=null)load(model);
 }
 void send(){
  if(Agent.busy){Toast.makeText(this,"Wait for the current operation or tap Stop",Toast.LENGTH_SHORT).show();return;}
  String question=composer.getText().toString().trim();
  if(question.isEmpty())return;
  File model=lastModel();
  if(Agent.loaded.isEmpty()&&model==null){
   Toast.makeText(this,"Load a model to send your message",Toast.LENGTH_LONG).show();
   page="Models";
   render();
   return;
  }
  if(chat.equals(queuedChat)){queuedChat=null;queuedVoice=null;}
  submit(chat,question,model);
 }
 private boolean dispatchVoice(){
  if(queuedVoice==null||Agent.busy)return false;
  if(!settings.getBoolean("autoSendVoice",true)){queuedChat=null;queuedVoice=null;return false;}
  File model=lastModel();
  if(Agent.loaded.isEmpty()&&model==null){
   List<File> available=models.files();
   if(available.size()==1)model=available.get(0);
   else{
    if(page.equals("Chat")&&chat.equals(queuedChat)){
     Toast.makeText(this,"Choose a model to send your voice message",Toast.LENGTH_LONG).show();
     page="Models";
     render();
    }
    return false;
   }
  }
  submitQueuedVoice(model);
  return true;
 }
 private void submitQueuedVoice(File model){
  String id=queuedChat, question=queuedVoice;
  queuedChat=null;
  queuedVoice=null;
  submit(id,question,model);
 }
 private void submit(String id,String question,File model){
  restoreChecked=true;
  tts.stop();
  mutedReply=false;
  Intent request=new Intent(this,AgentService.class).setAction("chat")
    .putExtra("chat",id).putExtra("question",question);
  if(model!=null)request.putExtra("path",model.getAbsolutePath());
  startForegroundService(request);
  if(chat.equals(id)){
   if(composer!=null&&composer.getText().toString().trim().equals(question))composer.setText("");
   if(chatDraft.trim().equals(question))chatDraft="";
  }
 }
 void updateStatus(){
  int percent=Agent.percent;
  String caption=Agent.status;
  if(Agent.busy){
   caption+=percent<0?"":" · "+percent+"%";
   caption+=Agent.tokensPerSecond>0?String.format(Locale.getDefault()," · %.1f tok/s",Agent.tokensPerSecond):"";
   caption+=String.format(Locale.getDefault()," · %.1fs",Math.max(0,SystemClock.elapsedRealtime()-Agent.phaseStarted)/1000.0);
  }else if(Agent.loaded.isEmpty())caption+=" · No model loaded";
  else if(!Agent.backend.isEmpty())caption+=" · "+Agent.backend;
  status.setText(caption);
  if(cacheStatus!=null){
   boolean ready=InstructionCache.ready(this,settings,cacheSystem);
   cacheStatus.setText(ready?"Instruction KV cache · Ready (saved on device)"
     :caption.startsWith("Caching initial instructions")?"Instruction KV cache · Preparing"
     :"Instruction KV cache · Not ready");
  }
  if(progress!=null){
   progress.setVisibility(Agent.busy?View.VISIBLE:View.INVISIBLE);
   progress.setIndeterminate(percent<0);
   if(percent>=0)progress.setProgress(percent);
  }
 }
 public void update(String id,String live){runOnUiThread(()->{
  if(!resumed)return;
  updateStatus();
  if(!Agent.busy&&dispatchVoice())return;
  if(!page.equals("Chat"))return;
  stopOrNew.setText(Agent.busy?"Stop":"New");
  if(!chat.equals(id))return;
  String latest=Agent.live;
  if(latest.isEmpty()&&Agent.liveThinking.isEmpty()){
   List<String[]> saved=Agent.store.messages(chat);
   if(saved.size()>renderedMessages){
    if(emptyChat!=null){body.removeView(emptyChat);emptyChat=null;}
    body.removeView(liveCards);
    boolean keepThinkingOpen=thinkingCard.getVisibility()==View.VISIBLE&&thinkingStream.getVisibility()==View.VISIBLE;
    for(int i=renderedMessages;i<saved.size();i++){
     String[] message=saved.get(i);
     if(keepThinkingOpen&&message[1].equals("reasoning")){
      thinkingDetails(card(body),message[2],true);
      keepThinkingOpen=false;
     }else messageCard(message[1],message[2]);
    }
    body.addView(liveCards);
    renderedMessages=saved.size();
   }
  }
  updateStreams(latest,Agent.liveThinking);
 });}
 public void speech(String id,long round,String text,boolean finished){
  streamSpeech(id,round,text,finished,false);
 }
 public void thinkingSpeech(String id,long round,String text,boolean finished){
  streamSpeech(id,round,text,finished,true);
 }
 void streamSpeech(String id,long round,String text,boolean finished,boolean thinking){runOnUiThread(()->{
  if(isDestroyed()||!chat.equals(id)||mutedReply||Agent.stopped||!ttsReady)return;
  if(!settings.getBoolean(thinking?"speakThinking":"speak",true))return;
  if(speechRound!=round){
   speechRound=round;
   sentences=new SentenceStream();
   thinkingSentences=new SentenceStream();
  }
  (thinking?thinkingSentences:sentences).append(text,finished,this::enqueueSpeech);
 });}
 void silence(){mutedReply=true;tts.stop();}
 void enqueueSpeech(String text){
  int max=TextToSpeech.getMaxSpeechInputLength();
  for(int start=0;start<text.length();){
   int end=Math.min(start+max,text.length());
   if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
   tts.speak(text.substring(start,end),TextToSpeech.QUEUE_ADD,null,"reply-"+(++utteranceId));
   start=end;
  }
 }
 void chooseChat(){List<String[]> chats=Agent.store.chats();String[] names=new String[chats.size()+1];names[0]="+ New conversation";for(int i=0;i<chats.size();i++)names[i+1]=chats.get(i)[1];new AlertDialog.Builder(this).setTitle("Conversations").setItems(names,(d,i)->{silence();chat=i==0?Agent.store.newChat():chats.get(i-1)[0];page="Chat";render();}).setNeutralButton("Delete current",(d,w)->confirm("Delete this conversation?",()->{if(Agent.busy&&chat.equals(Agent.activeChat)){error(new Exception("Stop this conversation before deleting it."));return;}Agent.store.deleteChat(chat);chat=Agent.store.newChat();render();})).setNegativeButton("Close",null).show();}
 void listen(){try{Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Talk to your agent");i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);startActivityForResult(i,10);}catch(ActivityNotFoundException e){error(new Exception("No speech recognizer is installed. Install or enable a speech service in Android settings."));}}
 void speakLast(){
  List<String[]> messages=Agent.store.messages(chat);
  for(int i=messages.size()-1;i>=0;i--){
   if(!messages.get(i)[1].equals("assistant"))continue;
   if(!ttsReady){error(new IllegalStateException("Choose or install an Android text-to-speech voice in system settings."));return;}
   silence();
   new SentenceStream().append(messages.get(i)[2],true,this::enqueueSpeech);
   return;
  }
 }
 void drawModels(){title(body,"Models");label(body,"Use a single-file instruct GGUF sized for your phone. Downloads continue through Android Download Manager. Wi-Fi is recommended for large models.");body.addView(button("Browse GGUF model catalog",this::catalog));LinearLayout add=row();add.addView(button("Download URL",()->{EditText e=input("https://…/model.gguf","",false);new AlertDialog.Builder(this).setTitle("Download GGUF").setView(e).setPositiveButton("Download",(d,w)->{try{models.download(e.getText().toString());render();}catch(Exception x){error(x);}}).setNegativeButton("Cancel",null).show();}));add.addView(button("GitHub releases",this::github));add.addView(button("Import",()->pick(11,"*/*")));body.addView(add);
  for(String[] d:models.downloads()){if(d[5].equals("8"))continue;LinearLayout c=card(body);c.addView(text(d[1],16,TEXT));label(c,d[2]+" · "+size(Long.parseLong(d[3]))+" / "+(Long.parseLong(d[4])>0?size(Long.parseLong(d[4])):"unknown size"));ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);long total=Long.parseLong(d[4]),done=Long.parseLong(d[3]);p.setIndeterminate(total<=0);p.setMax(1000);if(total>0)p.setProgress((int)(1000.0*done/total));c.addView(p);c.addView(button(d[5].equals("16")?"Remove failed download":"Cancel download",()->{models.remove(Long.parseLong(d[0]));render();}));}
  for(File f:models.files()){LinearLayout c=card(body);c.addView(text(f.getName(),16,TEXT));label(c,size(f.length())+(f.getName().equals(Agent.loaded)?" · Loaded":""));LinearLayout actions=row();actions.addView(button("Load",()->load(f)));actions.addView(button("Export GGUF",()->pickExport(f)));actions.addView(button("Delete",()->{if(Agent.busy)throw new IllegalStateException("Stop the current operation first.");confirm("Delete "+f.getName()+"?",()->{if(f.getName().equals(Agent.loaded)){AgentService.WORK.execute(()->NativeModel.unload());Agent.loaded="";Agent.instructionCache=null;}if(f.equals(lastModel()))settings.edit().remove("model").apply();boolean tracked=false;for(String[] d:models.downloads())if(d[1].equals(f.getName())){models.remove(Long.parseLong(d[0]));tracked=true;}if(!tracked)f.delete();render();});}));c.addView(actions);}
 }
 void catalog(){ScrollView sc=new ScrollView(this);LinearLayout list=column();list.setPadding(dp(12),dp(6),dp(12),dp(6));sc.addView(list);label(list,"Select a model to download. Size shown is disk size; inference also needs RAM for context and working memory.");for(ModelCatalog.Entry entry:ModelCatalog.ALL){LinearLayout c=card(list);c.addView(text(entry.name,16,TEXT));label(c,size(entry.bytes)+" · "+entry.note);LinearLayout actions=row();actions.addView(button("Download",()->new AlertDialog.Builder(this).setTitle(entry.name).setMessage("Download "+size(entry.bytes)+" from "+entry.repository+"? Check the publisher model card for its license.").setPositiveButton("Download",(d,w)->{try{models.download(entry.url());Toast.makeText(this,"Download started",Toast.LENGTH_SHORT).show();}catch(Exception e){error(e);}}).setNegativeButton("Cancel",null).show()));actions.addView(button("Publisher",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(entry.card())))));c.addView(actions);}new AlertDialog.Builder(this).setTitle("GGUF catalog").setView(sc).setPositiveButton("Done",(d,w)->render()).show();}
 String size(long n){return String.format(Locale.US,"%.1f MiB",Math.max(0,n)/1048576.0);}
 void load(File f){if(Agent.busy)throw new IllegalStateException("Stop the current operation first.");
  if(queuedVoice!=null&&settings.getBoolean("autoSendVoice",true)&&Agent.loaded.isEmpty()){
   if(chat.equals(queuedChat)){page="Chat";render();}
   submitQueuedVoice(f);
   return;
  }
  startForegroundService(new Intent(this,AgentService.class).setAction("load").putExtra("path",f.getAbsolutePath()));Toast.makeText(this,"Loading "+f.getName(),Toast.LENGTH_SHORT).show();}
 void github(){EditText e=input("owner/repository","",false);new AlertDialog.Builder(this).setTitle("Find GGUF release assets").setView(e).setPositiveButton("Find",(dialog,w)->{Toast.makeText(this,"Searching GitHub…",Toast.LENGTH_SHORT).show();async(()->{try{List<String[]> assets=Models.github(e.getText().toString().trim());runOnUiThread(()->{if(assets.isEmpty()){error(new Exception("No GGUF assets in the latest 30 releases. Paste a raw GitHub GGUF URL or another direct HTTPS model URL."));return;}String[] names=new String[assets.size()];for(int i=0;i<names.length;i++)names[i]=assets.get(i)[0]+" · "+size(Long.parseLong(assets.get(i)[2]));new AlertDialog.Builder(this).setTitle("Download a model").setItems(names,(d,i)->{try{models.download(assets.get(i)[1]);render();}catch(Exception x){error(x);}}).show();});}catch(Exception x){runOnUiThread(()->error(x));}});}).setNegativeButton("Cancel",null).show();}
 void pickExport(File file){
  Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT);
  create.addCategory(Intent.CATEGORY_OPENABLE);
  create.setType("application/octet-stream");
  create.putExtra(Intent.EXTRA_TITLE,file.getName());
  pendingExport=file.getName();
  startActivityForResult(create,EXPORT_GGUF);
 }
 void exportModel(String name,Uri destination){
  if(name==null||destination==null)return;
  Toast.makeText(this,"Exporting "+name+"…",Toast.LENGTH_SHORT).show();
  async(()->{
   try{
    File model=new File(models.folder(),name);
    Models.validate(model);
    long expected=model.length();
    long copied=0;
    try(InputStream in=new FileInputStream(model);
        OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
     if(out==null)throw new IOException("Unable to open the export destination.");
     byte[] buffer=new byte[1024*1024];
     for(int count;(count=in.read(buffer))!=-1;){
      out.write(buffer,0,count);
      copied+=count;
     }
     if(copied!=expected)throw new IOException("The model changed while it was being exported.");
    }
    runOnUiThread(()->Toast.makeText(this,"Exported "+name,Toast.LENGTH_LONG).show());
   }catch(IOException|RuntimeException e){
    boolean deleted=false;
    try{deleted=android.provider.DocumentsContract.deleteDocument(getContentResolver(),destination);}
    catch(Exception cleanup){e.addSuppressed(cleanup);}
    String detail=deleted?"":" Remove the incomplete file at the destination.";
    runOnUiThread(()->error(new IOException("GGUF export failed. "+e.getMessage()+detail,e)));
   }
  });
 }
 void pick(int request,String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType(type);i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,request);}
 void drawMemory(){title(body,"Knowledge cubes");label(body,Agent.memory.status());label(body,"Imported text is encoded as independent hypercube signatures used only for RAG. Relevant passages are pulled automatically for each question. The agent can also call knowledge.search. Past conversations are kept separately and are searched only on a tool request.");body.addView(button("Import text document",()->pick(12,"text/*")));body.addView(button("Add knowledge",()->{LinearLayout v=column();EditText name=input("Source name","",false),content=input("Knowledge text","",true);v.addView(name);v.addView(content);new AlertDialog.Builder(this).setTitle("Add source").setView(v).setPositiveButton("Save",(d,w)->{String s=content.getText().toString(),n=name.getText().toString().trim();if(s.trim().isEmpty())return;async(()->{Agent.memory.add(n.isEmpty()?"Manual source":n,s);runOnUiThread(this::render);});}).setNegativeButton("Cancel",null).show();}));for(String[] source:Agent.store.rows("SELECT source,COUNT(*) FROM passages GROUP BY source")){LinearLayout c=card(body);c.addView(text(source[0],16,TEXT));label(c,source[1]+" passages");c.addView(button("Delete source",()->confirm("Delete this knowledge source?",()->async(()->{Agent.store.getWritableDatabase().delete("passages","source=?",new String[]{source[0]});Agent.memory.changed();runOnUiThread(this::render);})))) ;}}
 void drawInstructions(){title(body,"System instructions");label(body,"Enabled instructions are compiled in the displayed order for every turn. Use ↑ and ↓ to change precedence. Tool definitions are appended automatically.");body.addView(button("Add instruction",()->editInstruction(null)));List<String[]> rows=Agent.store.rows("SELECT id,title,text,enabled,position FROM instructions ORDER BY position,id");for(int i=0;i<rows.size();i++){String[] r=rows.get(i);LinearLayout c=card(body);Switch enabled=new Switch(this);enabled.setText(r[1]);enabled.setTextColor(TEXT);enabled.setChecked(r[3].equals("1"));enabled.setOnCheckedChangeListener((b,on)->Agent.store.getWritableDatabase().execSQL("UPDATE instructions SET enabled=? WHERE id=?",new Object[]{on?1:0,r[0]}));c.addView(enabled);label(c,r[2]);LinearLayout actions=row();actions.addView(button("Edit",()->editInstruction(r)));final int idx=i;actions.addView(button("↑",()->moveInstruction(rows,idx,-1)));actions.addView(button("↓",()->moveInstruction(rows,idx,1)));actions.addView(button("Delete",()->confirm("Delete instruction?",()->{Agent.store.getWritableDatabase().delete("instructions","id=?",new String[]{r[0]});render();})));c.addView(actions);}}
 void moveInstruction(List<String[]> rows,int i,int delta){int j=i+delta;if(j<0||j>=rows.size())return;Collections.swap(rows,i,j);android.database.sqlite.SQLiteDatabase db=Agent.store.getWritableDatabase();db.beginTransaction();try{for(int k=0;k<rows.size();k++)db.execSQL("UPDATE instructions SET position=? WHERE id=?",new Object[]{k,rows.get(k)[0]});db.setTransactionSuccessful();}finally{db.endTransaction();}render();}
 void editInstruction(String[] r){LinearLayout v=column();EditText name=input("Instruction name",r==null?"":r[1],false),content=input("System instruction",r==null?"":r[2],true);v.addView(name);v.addView(content);new AlertDialog.Builder(this).setTitle("System instruction").setView(v).setPositiveButton("Save",(d,w)->{if(content.getText().toString().trim().isEmpty())return;ContentValues values=new ContentValues();values.put("title",name.getText().toString());values.put("text",content.getText().toString());if(r==null){values.put("enabled",1);values.put("position",Agent.store.rows("SELECT id FROM instructions").size());Agent.store.getWritableDatabase().insertOrThrow("instructions",null,values);}else Agent.store.getWritableDatabase().update("instructions",values,"id=?",new String[]{r[0]});render();}).setNegativeButton("Cancel",null).show();}
 void drawSettings(){title(body,"Settings");cacheSystem=Agent.store.instructions()+"\n"+Agent.TOOLS;cacheStatus=text("",14,MUTED);body.addView(cacheStatus);Switch speak=new Switch(this);speak.setText("Speak replies automatically");speak.setTextColor(TEXT);speak.setChecked(settings.getBoolean("speak",true));speak.setOnCheckedChangeListener((b,on)->{settings.edit().putBoolean("speak",on).apply();if(!on)silence();});body.addView(speak);label(body,"Voice input uses the installed Android speech recognizer and requests offline recognition. Availability and network use depend on your speech provider. Voice output uses Android text-to-speech.");body.addView(button("Android voice settings",()->startActivity(new Intent("com.android.settings.TTS_SETTINGS"))));title(body,"Generation");label(body,"Thinking: "+settings.getInt("thinkingTokens",0)+" tokens. Off gives direct answers; Short caps reasoning at 128 tokens. Changes apply on the next message.");for(int n:new int[]{0,128,256})body.addView(button(n==0?"Thinking off (default)":"Thinking · "+n+" tokens",()->{settings.edit().putInt("thinkingTokens",n).apply();render();}));label(body,"Answer length: "+settings.getInt("answerTokens",1536)+" tokens, separate from the thinking allowance. Older turns are trimmed to reserve output space; the current question is kept.");for(int n:new int[]{768,1536,3072})body.addView(button(n+" answer tokens",()->{settings.edit().putInt("answerTokens",n).apply();render();}));title(body,"Acceleration");
  Switch gpu=new Switch(this);
  gpu.setText("Use Vulkan when supported");
  gpu.setTextColor(TEXT);
  gpu.setChecked(settings.getBoolean("gpu",true));
  gpu.setOnCheckedChangeListener((button,enabled)->settings.edit().putBoolean("gpu",enabled).apply());
  body.addView(gpu);
  label(body,"Reload your model to apply this setting.");
  title(body,"Model context");label(body,"Current: "+settings.getInt("context",4096)+" tokens. Reload the model after changing this. Larger contexts use more RAM.");for(int n:new int[]{2048,4096,8192,16384,32768})body.addView(button(n+" tokens",()->{settings.edit().putInt("context",n).apply();render();}));label(body,"All model inference, knowledge retrieval and chat storage run locally. Downloads contact the URL you choose. Release 1.0.0 · llama.cpp + hypercube RAG.");}
 protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
 if(request==EXPORT_GGUF){
  String name=pendingExport;
  pendingExport=null;
  if(result==RESULT_OK&&data!=null)exportModel(name,data.getData());
  return;
 }
 if(result!=RESULT_OK||data==null)return;if(request==10){ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);if(words!=null&&!words.isEmpty()){page="Chat";render();String transcript=words.get(0);
 if(transcript!=null&&!transcript.trim().isEmpty()){
  composer.setText(transcript);
  composer.setSelection(composer.length());
  if(settings.getBoolean("autoSendVoice",true)){
   queuedVoice=transcript.trim();
   queuedChat=chat;
   if(resumed)dispatchVoice();
  }
 }}return;}Uri uri=data.getData();if(uri==null)return;Toast.makeText(this,"Importing…",Toast.LENGTH_SHORT).show();async(()->{try{if(request==11){File tmp=new File(models.folder(),"import-"+UUID.randomUUID()+".part");try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(tmp)){byte[] buf=new byte[1024*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}Models.validate(tmp);String name="imported-"+System.currentTimeMillis()+".gguf";try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String candidate=c.getString(0);if(candidate!=null&&candidate.matches("[A-Za-z0-9_. -]+\\.gguf"))name=candidate;}}File target=new File(models.folder(),name);if(target.exists()||!tmp.renameTo(target))throw new IOException("Cannot save model: file name already exists or storage is full.");}finally{tmp.delete();}}else if(request==12){ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uri)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>8*1024*1024)throw new IOException("Import text files up to 8 MiB at a time.");}}Agent.memory.add(uri.getLastPathSegment(),b.toString("UTF-8"));}runOnUiThread(this::render);}catch(Exception e){runOnUiThread(()->error(e));}});}
}

package com.mobileagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class InstructionCache {
 private final SharedPreferences settings;
 private final String identity;
 private final String backend;
 private String preparedStamp="";
 final File file;

 InstructionCache(Context context,File model,String backend,int tokens){
  settings=context.getSharedPreferences("settings",0);
  identity=identity(model,tokens,backend);
  this.backend=backend;
  file=new File(context.getNoBackupFilesDir(),"instructions-v1-"+hash(identity)+".kv");
  // Build 21 saved the same native state format in Android's purgeable cache.
  File previous=new File(context.getCacheDir(),"instructions-"+hash(identity+"\n21")+".kv");
  if(!file.exists()&&previous.isFile())previous.renameTo(file);
 }

 void prepare(String system,String tools,boolean thinking,boolean warm,NativeModel.Tokens progress){
  preparedStamp=stamp(identity,system,thinking);
  markReady(NativeModel.preparePrefix(file.getPath(),system,tools,thinking,warm,progress));
 }

 void markReady(boolean ready){
  String stamp=ready?preparedStamp:"";
  if(file.getName().equals(settings.getString("cacheFile",""))&&stamp.equals(settings.getString("cacheStamp","")))return;
  settings.edit().putString("cacheBackend",backend)
    .putString("cacheFile",file.getName()).putString("cacheStamp",stamp).apply();
 }

 static boolean ready(Context context,SharedPreferences settings,String system){
  String path=settings.getString("model","");
  String backend=settings.getString("cacheBackend","");
  String name=settings.getString("cacheFile","");
  File model=new File(path);
  if(path.isEmpty()||backend.isEmpty()||name.isEmpty()||!model.isFile())return false;
  String identity=identity(model,settings.getInt("context",4096),backend);
  if(!settings.getBoolean("gpu",true)&&!backend.equals("CPU"))return false;
  File file=new File(context.getNoBackupFilesDir(),name);
  return file.length()>0&&settings.getString("cacheStamp","").equals(
    stamp(identity,system,settings.getInt("thinkingTokens",0)>0));
 }

 private static String identity(File model,int tokens,String backend){
  String path=model.getAbsolutePath();
  try{path=model.getCanonicalPath();}catch(IOException ignored){}
  return path+"\n"+model.length()+"\n"+model.lastModified()+"\n"+tokens+"\n"+backend;
 }

 private static String stamp(String identity,String system,boolean thinking){
  return hash(identity+"\n"+thinking+"\n"+system);
 }

 private static String hash(String text){
  try{
   return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)),
     Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
  }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
 }
}

package com.mobileagent;
public final class NativeModel {
 static { System.loadLibrary("mobileagent"); }
 /** Each callback is a complete UTF-8 JSON snapshot, not a raw token delta. */
 public interface Tokens { void token(byte[] utf8); }
 public static void load(String path,int context,int threads){
  loadBackend(path,context,threads,false);
 }
 static native String loadBackend(String path,int context,int threads,boolean preferGpu);
 static native boolean preparePrefix(String file,String system,String tools,boolean thinking,Tokens callback);
 public static native void unload();
 public static native byte[] generate(String messagesJson,String toolsJson,int answerTokens,int thinkingTokens,boolean requireTool,Tokens callback);
 public static native void stop();
}

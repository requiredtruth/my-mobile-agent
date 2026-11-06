package com.mobileagent;
public final class NativeModel {
 static { System.loadLibrary("mobileagent"); }
 public interface Tokens { void token(byte[] utf8); }
 public static native void load(String path,int context,int threads);
 public static native void unload();
 public static native byte[] generate(String[] roles,String[] texts,int maxTokens,Tokens callback);
 public static native void stop();
}

package com.mobileagent;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.function.Consumer;

/** Advances through cumulative reply snapshots in speech queue order. */
final class SentenceStream {
 private String queuedPrefix="";

 void append(String text,boolean finished,Consumer<String> enqueue){
  if(!text.startsWith(queuedPrefix))return;
  BreakIterator sentences=BreakIterator.getSentenceInstance(Locale.getDefault());
  sentences.setText(text);
  int start=queuedPrefix.length();
  for(int end=sentences.following(start);end!=BreakIterator.DONE;end=sentences.next()){
   String sentence=text.substring(start,end).trim();
   if(!finished&&end==text.length())break;
   if(!sentence.isEmpty())enqueue.accept(sentence);
   start=end;
   queuedPrefix=text.substring(0,end).trim();
  }
 }
}

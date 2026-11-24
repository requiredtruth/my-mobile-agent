package com.mobileagent;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.function.Consumer;

/** Advances through cumulative reply snapshots in speech queue order. */
final class SentenceStream {
 private String processedPrefix="";
 private String firstSentence;
 private boolean started;

 void append(String text,boolean finished,Consumer<String> enqueue){
  if(!text.startsWith(processedPrefix))return;
  BreakIterator sentences=BreakIterator.getSentenceInstance(Locale.getDefault());
  sentences.setText(text);
  int start=processedPrefix.length();
  for(int end=sentences.following(start);end!=BreakIterator.DONE;end=sentences.next()){
   String sentence=text.substring(start,end).trim();
   if(!finished&&end==text.length())break;
   if(!sentence.isEmpty())accept(sentence,enqueue);
   start=end;
   processedPrefix=text.substring(0,end).trim();
  }
  if(finished&&firstSentence!=null){
   enqueue.accept(firstSentence);
   firstSentence=null;
   started=true;
  }
 }

 private void accept(String sentence,Consumer<String> enqueue){
  if(!started){
   if(firstSentence==null){
    firstSentence=sentence;
    return;
   }
   enqueue.accept(firstSentence+" "+sentence);
   firstSentence=null;
   started=true;
   return;
  }
  enqueue.accept(sentence);
 }
}

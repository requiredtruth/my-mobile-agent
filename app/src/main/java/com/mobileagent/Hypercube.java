package com.mobileagent;
import java.util.*;
import java.io.*;
/** Standalone RAG signature: a vertex of a 4096-dimensional bipolar hypercube.
 * Bundles deterministic token vectors; Hamming similarity retrieves passages only.
 * No language generation, training engine, or external project code. */
public final class Hypercube {
 public static final int DIMENSIONS=4096,WORDS=DIMENSIONS/64;
 private static final Set<String> STOP=new HashSet<>(Arrays.asList("the","and","that","this","with","from","what","when","where","which","have","does","about","please","could","would","there","they","your","into","for","are","was","how","can","you"));
 public static List<String> terms(String text){TreeSet<String> terms=new TreeSet<>();for(String word:text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(word.length()>2&&!STOP.contains(word)){terms.add(word);if(terms.size()==128)break;}return new ArrayList<>(terms);}
 public static long[] encode(String text){List<String> terms=terms(text);if(terms.isEmpty())return null;int[] sums=new int[DIMENSIONS];
  for(String term:terms){Random random=new Random(0x65D782B91A4C3E0FL^((long)term.hashCode()<<17)^term.length());for(int w=0;w<WORDS;w++){long pattern=random.nextLong();for(int b=0;b<64;b++)sums[w*64+b]+=((pattern>>>b)&1)==1?1:-1;}}
  Random ties=new Random(terms.toString().hashCode());long[] packed=new long[WORDS];for(int i=0;i<DIMENSIONS;i++)if(sums[i]>0||(sums[i]==0&&ties.nextBoolean()))packed[i/64]|=1L<<(i%64);return packed;
 }
 public static double similarity(long[] a,long[] b){if(a==null||b==null)return 0;if(a.length!=WORDS||b.length!=WORDS)throw new IllegalArgumentException("Invalid hypercube dimensions");int distance=0;for(int i=0;i<WORDS;i++)distance+=Long.bitCount(a[i]^b[i]);return 1.0-2.0*distance/DIMENSIONS;}
 public static void write(DataOutput out,long[] signature)throws IOException{out.writeBoolean(signature!=null);if(signature!=null)for(long word:signature)out.writeLong(word);}
 public static long[] read(DataInput in)throws IOException{if(!in.readBoolean())return null;long[] signature=new long[WORDS];for(int i=0;i<WORDS;i++)signature[i]=in.readLong();return signature;}
}

import com.mobileagent.Hypercube;
import java.io.*;
import java.util.*;
public class CubeTest {
 public static void main(String[] args)throws Exception{
  long[] telescope=Hypercube.encode("The lunar telescope uses a silver mirror to collect moonlight and reveal craters.");long[] query=Hypercube.encode("lunar telescope moonlight");long[] other=Hypercube.encode("Bread recipes need flour yeast butter kitchen cooking.");
  if(Hypercube.similarity(query,telescope)<=Hypercube.similarity(query,other)+.08)throw new AssertionError("Relevant passage must outrank unrelated text");
  if(!Arrays.equals(query,Hypercube.encode("moonlight telescope lunar")))throw new AssertionError("Order independence");
  if(Hypercube.encode("the and for")==null){}else throw new AssertionError("Empty query should not retrieve");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();Hypercube.write(new DataOutputStream(bytes),telescope);if(!Arrays.equals(telescope,Hypercube.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))))throw new AssertionError("Cache round trip");
  if(Hypercube.similarity(query,query)!=1)throw new AssertionError("Identical coordinates");
  System.out.println("PASS: independent RAG hypercube ranking, deterministic coordinates, empty query, disk cache");
 }
}

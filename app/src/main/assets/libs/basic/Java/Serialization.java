import java.io.*;
import java.util.*;
public class SerializationSample implements Serializable{
    int x = 5;
    public static void main(String[] args) throws Exception{
        ObjectOutputStream o = new ObjectOutputStream(new java.io.FileOutputStream("/tmp/ser.bin"));
        o.writeObject(new SerializationSample()); o.close();
    }
}

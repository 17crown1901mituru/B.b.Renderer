import java.net.*;import java.io.*;
public class HttpClientSample {
    public static void main(String[] args) throws Exception{
        URL u = new URL("http://example.com");
        try(BufferedReader r = new BufferedReader(new InputStreamReader(u.openStream()))){
            System.out.println(r.readLine());
        }
    }
}

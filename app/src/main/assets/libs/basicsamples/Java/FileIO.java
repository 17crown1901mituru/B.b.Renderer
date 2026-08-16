import java.nio.file.*;
public class FileIOSample {
    public static void main(String[] args) throws Exception{
        Path p = Paths.get("/tmp/java_sample.txt");
        Files.write(p, "hello".getBytes());
        System.out.println(new String(Files.readAllBytes(p)));
    }
}

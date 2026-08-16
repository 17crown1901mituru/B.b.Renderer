import java.util.concurrent.*;
public class ConcurrencySample {
    public static void main(String[] args) throws Exception{
        ExecutorService es = Executors.newFixedThreadPool(2);
        Future<Integer> f = es.submit(() -> 42);
        System.out.println(f.get());
        es.shutdown();
    }
}

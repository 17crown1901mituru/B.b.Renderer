public class ThreadsSample {
    public static void main(String[] args) throws InterruptedException{
        Thread t = new Thread(() -> System.out.println("hello from thread"));
        t.start(); t.join();
    }
}

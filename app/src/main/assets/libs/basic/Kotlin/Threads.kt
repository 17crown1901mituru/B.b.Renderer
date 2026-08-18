import java.lang.Thread;
fun main() {
    val t = Thread {
        println("thread")
    };
    t.start();
    t.join()
}
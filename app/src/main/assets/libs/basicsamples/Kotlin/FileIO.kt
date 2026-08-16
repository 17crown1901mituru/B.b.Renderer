import java.io.File
fun main() {
    File("/tmp/kt.txt").writeText("hi");
    println(File("/tmp/kt.txt").readText())
}
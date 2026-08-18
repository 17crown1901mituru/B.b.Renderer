fun <T> makeList(vararg items: T) = items.toList()
fun main() {
    println(makeList(1, 2, 3))
}
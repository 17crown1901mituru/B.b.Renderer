fun parse(s: String): Int {
    return try {
        s.toInt()
    } catch (e: NumberFormatException) {
        0
    }
}
fun main() {
    println(parse("12a"))
}
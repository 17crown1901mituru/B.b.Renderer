class MyEx(msg: String): Exception(msg)
fun main() {
    try {
        throw MyEx("boom")
    } catch (e: MyEx) {
        println(e.message)
    }
}
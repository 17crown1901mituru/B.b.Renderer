fun main() {
    val seq = sequenceOf(1, 2, 3).filter {
        it % 2 == 1
    };
    seq.forEach {
        println(it)
    }
}
public class Conditionals {
    public static String check(int n) {
        if (n % 2 == 0) return "even";
        else return "odd";
    }
    public static void main(String[] args) {
        System.out.println(check(5));
    }
}
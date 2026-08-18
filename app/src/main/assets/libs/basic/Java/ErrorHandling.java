public class ErrorHandling {
    public static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            System.out.println("bad");
            return 0;
        }
    }
    public static void main(String[] args) {
        System.out.println(parse("12a"));
    }
}
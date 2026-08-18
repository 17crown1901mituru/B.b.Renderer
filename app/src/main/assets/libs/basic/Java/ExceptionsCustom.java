class MyEx extends Exception {
    MyEx(String m) {
        super(m);
    }
}
public class CustomException {
    public static void main(String[] args) {
        try {
            throw new MyEx("boom");
        } catch (MyEx e) {
            System.out.println(e.getMessage());
        }
    }
}
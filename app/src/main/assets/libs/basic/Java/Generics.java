import java.util.*;
public class Generics {
    public static <T> List <T> makeList(T...items) {
        List <T> l = new ArrayList <> ();
        for (T it: items) l.add(it);
        return l;
    }
    public static void main(String[] args) {
        System.out.println(makeList(1, 2, 3));
    }
}
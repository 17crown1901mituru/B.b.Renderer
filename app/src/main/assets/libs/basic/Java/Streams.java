import java.util.*;
public class StreamsSample {
    public static void main(String[] args){
        List<Integer> l = Arrays.asList(1,2,3,4);
        l.stream().filter(x->x%2==0).forEach(System.out::println);
    }
}

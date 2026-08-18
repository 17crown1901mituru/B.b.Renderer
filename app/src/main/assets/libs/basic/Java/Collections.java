import java.util.*;
public class CollectionsSample {
    public static void main(String[] args){
        Map<String,Integer> m = new HashMap<>();
        m.put("a",1); m.put("b",2);
        m.forEach((k,v)->System.out.println(k+":"+v));
    }
}

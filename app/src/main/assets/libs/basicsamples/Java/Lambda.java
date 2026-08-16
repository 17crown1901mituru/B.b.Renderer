import java.util.*;
public class LambdaSample {
    interface IntOp { int apply(int x); }
    public static void main(String[] args){
        IntOp doub = x -> x*2;
        System.out.println(doub.apply(5));
    }
}

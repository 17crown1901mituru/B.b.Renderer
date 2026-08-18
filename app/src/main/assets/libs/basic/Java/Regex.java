import java.util.regex.*;
public class RegexSample {
    public static void main(String[] args){
        Pattern p = Pattern.compile("\\d+");
        System.out.println(p.matcher("abc123").find());
    }
}

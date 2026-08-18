import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@interface Note {
    String value();
}
@Note("demo")
public class AnnotationSample {
    public static void main(String[] args) {
        System.out.println("AnnotationSample");
    }
}
package tags;

import java.lang.annotation.*;

@org.scalatest.TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface BlazegraphOnly { }

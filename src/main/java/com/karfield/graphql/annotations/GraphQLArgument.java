package com.karfield.graphql.annotations;

import java.lang.annotation.*;

@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GraphQLArgument {
    String value() default "";
    String name() default "";
}

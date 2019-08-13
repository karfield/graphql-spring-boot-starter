package com.karfield.graphql.annotations;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableGraphQL {
    String schema() default "schema.graphqls";
}

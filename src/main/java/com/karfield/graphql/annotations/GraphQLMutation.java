package com.karfield.graphql.annotations;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Inherited
public @interface GraphQLMutation {
    String field();
    String type() default "";
}

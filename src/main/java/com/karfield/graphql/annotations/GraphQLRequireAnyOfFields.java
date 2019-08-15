package com.karfield.graphql.annotations;

import java.lang.annotation.*;

@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GraphQLRequireAnyOfFields {
    /**
     * This will return true if the field selection set matches any of the specified "glob" pattern matches ie
     * the glob pattern matching supported by {@link java.nio.file.FileSystem#getPathMatcher}.
     *
     * This will allow you to use '*', '**' and '?' as special matching characters such that "invoice/customer*" would
     * match an invoice field with child fields that start with 'customer'.
     *
     */
    String[] value() default "";
}

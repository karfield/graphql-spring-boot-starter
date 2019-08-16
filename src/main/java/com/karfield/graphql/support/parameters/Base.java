package com.karfield.graphql.support.parameters;

import com.karfield.graphql.support.ResolverParameter;
import lombok.Data;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;

@Data
public abstract class Base implements ResolverParameter {
    protected Parameter parameter;
    protected Annotation annotation;
}

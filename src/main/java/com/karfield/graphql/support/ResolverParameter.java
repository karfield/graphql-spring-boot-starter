package com.karfield.graphql.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;

public interface ResolverParameter {
     Parameter getParameter();
     Annotation getAnnotation();
}

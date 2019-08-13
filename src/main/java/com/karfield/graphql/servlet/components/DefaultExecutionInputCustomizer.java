package com.karfield.graphql.servlet.components;

import com.karfield.graphql.servlet.ExecutionInputCustomizer;
import graphql.ExecutionInput;
import graphql.Internal;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.concurrent.CompletableFuture;

@Component
@Internal
public class DefaultExecutionInputCustomizer implements ExecutionInputCustomizer {

    @Override
    public CompletableFuture<ExecutionInput> customizeExecutionInput(ExecutionInput executionInput, WebRequest webRequest) {
        return CompletableFuture.completedFuture(executionInput);
    }
}

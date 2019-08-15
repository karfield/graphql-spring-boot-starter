package com.karfield.graphql.servlet.components;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Maps;
import com.karfield.graphql.servlet.ExecutionResultHandler;
import com.karfield.graphql.servlet.GraphQLInvocation;
import com.karfield.graphql.servlet.GraphQLInvocationData;
import com.karfield.graphql.servlet.JsonSerializer;
import graphql.ExecutionResult;
import graphql.Internal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@Internal
public class GraphQLController {

    @Autowired
    GraphQLInvocation graphQLInvocation;

    @Autowired
    ExecutionResultHandler executionResultHandler;

    @Autowired
    JsonSerializer jsonSerializer;

    @RequestMapping(value = "${graphql.endpoint:graphql}",
            method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Object graphqlPOST(
            @RequestHeader HttpHeaders httpHeaders,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "operationName", required = false) String operationName,
            @RequestParam(value = "variables", required = false) String variablesJson,
            @RequestBody(required = false) String body,
            WebRequest webRequest) throws IOException {

        if (body == null) {
            body = "";
        }

        MediaType contentType = httpHeaders.getContentType();

        // https://graphql.org/learn/serving-over-http/#post-request
        //
        // A standard GraphQL POST request should use the application/json content type,
        // and include a JSON-encoded body of the following form:
        //
        // {
        //   "query": "...",
        //   "operationName": "...",
        //   "variables": { "myVariable": "someValue", ... }
        // }

        if (MediaType.APPLICATION_JSON.equals(contentType)) {
            GraphQLRequestBody request = jsonSerializer.deserialize(body, GraphQLRequestBody.class);
            if (request.getQuery() == null) {
                request.setQuery("");
            }
            return executeRequest(request.getQuery(), request.getOperationName(), request.getVariables(), webRequest, httpHeaders);
        }

        // In addition to the above, we recommend supporting two additional cases:
        //
        // * If the "query" query string parameter is present (as in the GET example above),
        //   it should be parsed and handled in the same way as the HTTP GET case.

        if (query != null) {
            return executeRequest(query, operationName, convertVariablesJson(variablesJson), webRequest, httpHeaders);
        }

        // * If the "application/graphql" Content-Type header is present,
        //   treat the HTTP POST body contents as the GraphQL query string.

        if ("application/graphql".equals(contentType)) {
            return executeRequest(body, null, null, webRequest, httpHeaders);
        }

        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not process GraphQL request");
    }

    @RequestMapping(value = "${graphql.endpoint:graphql}",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Object graphqlGET(
            @RequestHeader HttpHeaders httpHeaders,
            @RequestParam("query") String query,
            @RequestParam(value = "operationName", required = false) String operationName,
            @RequestParam(value = "variables", required = false) String variablesJson,
            WebRequest webRequest) {

        // https://graphql.org/learn/serving-over-http/#get-request
        //
        // When receiving an HTTP GET request, the GraphQL query should be specified in the "query" query string.
        // For example, if we wanted to execute the following GraphQL query:
        //
        // {
        //   me {
        //     name
        //   }
        // }
        //
        // This request could be sent via an HTTP GET like so:
        //
        // http://myapi/graphql?query={me{name}}
        //
        // Query variables can be sent as a JSON-encoded string in an additional query parameter called "variables".
        // If the query contains several named operations,
        // an "operationName" query parameter can be used to control which one should be executed.

        return executeRequest(query, operationName, convertVariablesJson(variablesJson), webRequest, httpHeaders);
    }

    private Map<String, Object> convertVariablesJson(String jsonMap) {
        if (jsonMap == null) {
            return Collections.emptyMap();
        }
        return jsonSerializer.deserialize(jsonMap, Map.class);
    }

    @Autowired
    private String passXHeader;

    private Object executeRequest(
            String query,
            String operationName,
            Map<String, Object> variables,
            WebRequest webRequest,
            HttpHeaders httpHeaders
            ) {
        HashMap<String, Object> xHeaders = Maps.newHashMap();
        if (!passXHeader.equals("")) {
            Map<String, String> headers = httpHeaders.toSingleValueMap();
            headers.forEach((k, v) -> {
                k = k.toLowerCase();
                if (k.startsWith(passXHeader)) {
                    // user defined headers, we pass it as a variables
//                    k = CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, k);
                    xHeaders.put(k, v);
                }
            });
        }
        GraphQLInvocationData invocationData = new GraphQLInvocationData(query, operationName, variables);
        CompletableFuture<ExecutionResult> executionResult = graphQLInvocation.invoke(invocationData, webRequest, xHeaders);
        return executionResultHandler.handleExecutionResult(executionResult);
    }

}

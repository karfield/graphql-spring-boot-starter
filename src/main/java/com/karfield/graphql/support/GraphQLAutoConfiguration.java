package com.karfield.graphql.support;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.karfield.graphql.annotations.GraphQLArgument;
import com.karfield.graphql.annotations.*;
import com.karfield.graphql.servlet.components.GraphQLController;
import graphql.GraphQL;
import graphql.schema.*;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

@Configuration
@ConditionalOnWebApplication
@ComponentScan(basePackageClasses = {DataFetcher.class, Coercing.class, GraphQLController.class})
public class GraphQLAutoConfiguration {

    @Autowired
    ApplicationContext applicationContext;

    private GraphQL graphQL;

    @Bean
    public GraphQL graphQL() {
        return graphQL;
    }

    private String passXHeader = "";

    @Bean
    public String passXHeader() {
        return passXHeader;
    }

    @PostConstruct
    public void init() throws IOException {
        EnableGraphQL config = getGraphQLConfig();
        if (config == null) {
            return;
        }

        String headerPrefix = config.xHeaderPrefix().toLowerCase();
        if (headerPrefix.startsWith("x-")) {
            passXHeader = headerPrefix;
        }

        URL url = Resources.getResource(config.schema());
        String sdl = Resources.toString(url, Charsets.UTF_8);

        List<String> others = Lists.newArrayList("common.graphql");
        config.modules();
        for (String m: config.modules()) {
            if (!m.equals("common.graphql")) {
                others.add(m);
            }
        }
        for (String m: others) {
            try {
                url = Resources.getResource(m);
                String s = Resources.toString(url, Charsets.UTF_8);
                sdl += "\n";
                sdl += s;
            } catch (IllegalArgumentException e) {
                continue;
            }
        }

        GraphQLSchema graphQLSchema = buildSchema(sdl);
        this.graphQL = GraphQL.newGraphQL(graphQLSchema).build();
    }

    private GraphQLSchema buildSchema(String sdl) {
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sdl);
        RuntimeWiring runtimeWiring = buildWiring();
        SchemaGenerator schemaGenerator = new SchemaGenerator();
        return schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring);
    }

    private RuntimeWiring buildWiring() {
        RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();

        List<WiringPair<GraphQLScalar>> scalars = scanWirings(GraphQLScalar.class);
        for (WiringPair<GraphQLScalar> scalar: scalars) {
            if (scalar.instance instanceof Coercing) {
                builder = builder.scalar(new GraphQLScalarType(
                        scalar.ann.name(), scalar.ann.description(), (Coercing) scalar.instance));
            }
        }

        List<WiringPair<GraphQLQuery>> queries = scanWirings(GraphQLQuery.class);
        for (WiringPair<GraphQLQuery> query: queries) {
            if (query.instance instanceof DataFetcher) {
                builder = wireQuery(builder, query.ann.type(), query.ann.field(), (DataFetcher) query.instance);
            }
        }

        List<WiringPair<GraphQLMutation>> mutations = scanWirings(GraphQLMutation.class);
        for (WiringPair<GraphQLMutation> mutation: mutations) {
            if (mutation.instance instanceof DataFetcher) {
                builder = wireMutation(builder, mutation.ann.type(), mutation.ann.field(), (DataFetcher) mutation.instance);
            }
        }

        List<WiringPair<GraphQLInterface>> interfaces = scanWirings(GraphQLInterface.class);
        for (WiringPair<GraphQLInterface> intf: interfaces) {
            if (intf.instance instanceof TypeResolver) {
                builder = builder.type(newTypeWiring(intf.ann.value()).typeResolver((TypeResolver) intf.instance));
            }
        }

        List<WiringPair<GraphQLUnion>> unions = scanWirings(GraphQLUnion.class);
        for (WiringPair<GraphQLUnion> union: unions) {
            if (union.instance instanceof TypeResolver) {
                builder = builder.type(newTypeWiring(union.ann.value()).typeResolver((TypeResolver) union.instance));
            }
        }

        List<WiringPair<GraphQLResolver>> resolvers = scanWirings(GraphQLResolver.class);
        for (WiringPair<GraphQLResolver> resolver: resolvers) {
            builder = registerResolver(builder, resolver.instance);
        }

        return builder.build();
    }

    private RuntimeWiring.Builder wireType(RuntimeWiring.Builder builder, String name, String defaultName, String field, DataFetcher instance) {
        if (name.equals("")) {
            name = defaultName;
        }
        return builder.type(newTypeWiring(name).dataFetcher(field, instance));
    }

    private RuntimeWiring.Builder wireQuery(RuntimeWiring.Builder builder, String name, String field, DataFetcher instance) {
        return wireType(builder, name, "Query", field, instance);
    }

    private RuntimeWiring.Builder wireMutation(RuntimeWiring.Builder builder, String name, String field, DataFetcher instance) {
        return wireType(builder, name, "Mutation", field, instance);
    }

    private EnableGraphQL getGraphQLConfig() {
        String[] beanNames = applicationContext.getBeanNamesForAnnotation(EnableGraphQL.class);
        if (beanNames.length == 0) {
            return null;
        }
        return applicationContext.findAnnotationOnBean(beanNames[0], EnableGraphQL.class);
    }

    private class WiringPair<T extends Annotation> {
        Object instance;
        T ann;

        public WiringPair(Object instance, T ann) {
            this.instance = instance;
            this.ann = ann;
        }
    }

    private <T extends Annotation> List<WiringPair<T>> scanWirings(Class<T> ann) {
        String[] names = applicationContext.getBeanNamesForAnnotation(ann);
        ArrayList<WiringPair<T>> results = Lists.newArrayList();
        for (String name: names) {
            Object obj = applicationContext.getBean(name);
            T a = applicationContext.findAnnotationOnBean(name, ann);
            results.add(new WiringPair<T>(obj, a));
        }
        return results;
    }

    private RuntimeWiring.Builder registerResolver(RuntimeWiring.Builder builder, Object resolver) {
        for (Method method: resolver.getClass().getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            GraphQLQuery query = method.getAnnotation(GraphQLQuery.class);
            if (query != null) {
                builder = wireQuery(builder, query.type(), query.field(), dataFetchingEnvironment ->
                        method.invoke(resolver, buildInvokeParameters(dataFetchingEnvironment, method.getParameters())));
                continue;
            }

            GraphQLMutation mutation = method.getAnnotation(GraphQLMutation.class);
            if (mutation != null) {
                builder = wireQuery(builder, mutation.type(), mutation.field(), dataFetchingEnvironment ->
                        method.invoke(resolver, buildInvokeParameters(dataFetchingEnvironment, method.getParameters())));
            }
        }
        return builder;
    }

    private Object[] buildInvokeParameters(DataFetchingEnvironment dataFetchingEnvironment, Parameter[] parameters) {
        List<Object> arguments = Lists.newArrayList();
        for (Parameter p : parameters) {
            if (p.getAnnotations().length > 0) {
                GraphQLArgument arg = p.getAnnotation(GraphQLArgument.class);
                if (arg != null) {
                    String name = arg.name();
                    if (name.equals("")) {
                        name = arg.value();
                    }
                    Object a = dataFetchingEnvironment.getArgument(name);
                    arguments.add(a);
                    continue;
                }

                GraphQLContext ctx = p.getAnnotation(GraphQLContext.class);
                if (ctx != null) {
                    Object context = dataFetchingEnvironment.getContext();
                    if (ctx.key().equals("")) {
                        arguments.add(context);
                    } else if (context instanceof Map) {
                        arguments.add(((Map) context).get(ctx.key()));
                    }
                    continue;
                }

                GraphQLRequireAnyOfFields fr = p.getAnnotation(GraphQLRequireAnyOfFields.class);
                if (fr != null) {
                    if (p.getClass().equals(Boolean.class) || p.getClass().equals(boolean.class)) {
                        if (fr.value().length == 0) {
                            arguments.add(true);
                            continue;
                        }
                        String glob = fr.value()[0];
                        boolean result;
                        if (fr.value().length > 1) {
                            ArrayList<String> anyOf = Lists.newArrayList(fr.value());
                            String[] others = (String[]) anyOf.subList(1, anyOf.size() - 1).toArray();
                            result = dataFetchingEnvironment.getSelectionSet().containsAnyOf(glob, others);
                        } else {
                            result = dataFetchingEnvironment.getSelectionSet().containsAnyOf(glob);
                        }
                        arguments.add(result);
                        continue;
                    } else {
                        throw new RuntimeException("@GraphQLRequireAnyOfFields only can be used on boolean parameter");
                    }
                }

                GraphQLRequireAnyOfFields ar = p.getAnnotation(GraphQLRequireAnyOfFields.class);
                if (ar != null) {
                    if (p.getClass().equals(Boolean.class) || p.getClass().equals(boolean.class)) {
                        if (ar.value().length == 0) {
                            arguments.add(false);
                            continue;
                        }
                        String glob = ar.value()[0];
                        boolean result;
                        if (ar.value().length > 1) {
                            ArrayList<String> allOf = Lists.newArrayList(ar.value());
                            String[] others = (String[]) allOf.subList(1, allOf.size() - 1).toArray();
                            result = dataFetchingEnvironment.getSelectionSet().containsAllOf(glob, others);
                        } else {
                            result = dataFetchingEnvironment.getSelectionSet().containsAllOf(glob);
                        }
                        arguments.add(result);
                    } else {
                        throw new RuntimeException("@GraphQLRequireAllOfFields only can be used on boolean parameter");
                    }
                }

                GraphQLPath path = p.getAnnotation(GraphQLPath.class);
                if (path != null) {
                    if (p.getClass().equals(String.class)) {
                        String pth = dataFetchingEnvironment.getExecutionStepInfo().getPath().toString();
                        arguments.add(pth);
                        continue;
                    } else {
                        throw new RuntimeException("@GraphQLPath only can be used on String parameter");
                    }
                }

                // unknown parameter
                throw new RuntimeException("unknown argument");
            } else {
                if (p.getType().equals(DataFetchingEnvironment.class)) {
                    arguments.add(dataFetchingEnvironment);
                    continue;
                } else {
                    throw new RuntimeException("unknown type of argument without annotation, recommend to use DataFetchingEnvironment as a parameter");
                }
            }
        }
        return arguments.toArray();
    }
}

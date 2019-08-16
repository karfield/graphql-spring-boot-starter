package com.karfield.graphql.support;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.karfield.graphql.annotations.GraphQLArgument;
import com.karfield.graphql.annotations.*;
import com.karfield.graphql.servlet.components.GraphQLController;
import com.karfield.graphql.support.parameters.*;
import graphql.GraphQL;
import graphql.execution.ExecutionPath;
import graphql.schema.*;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;

import javax.annotation.PostConstruct;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

@Slf4j
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
    public void init() throws Exception {
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

    private GraphQLSchema buildSchema(String sdl) throws Exception {
        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sdl);
        RuntimeWiring runtimeWiring = buildWiring();
        SchemaGenerator schemaGenerator = new SchemaGenerator();
        return schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring);
    }

    private RuntimeWiring buildWiring() throws Exception {
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
        log.info("install resolver for " + name + "." + field);
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

    private RuntimeWiring.Builder registerResolver(RuntimeWiring.Builder builder, Object resolver) throws Exception {
        for (Method method: resolver.getClass().getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals("getUser")) {
                System.out.println("ppp");
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            GraphQLQuery query = AnnotationUtils.findAnnotation(method, GraphQLQuery.class);
            if (query != null) {
                List<ResolverParameter> resolverParams = checkParameters(method);
                builder = wireQuery(builder, query.type(), query.field(), dataFetchingEnvironment ->
                        method.invoke(resolver, buildInvokeParameters(dataFetchingEnvironment, resolverParams)));
                continue;
            }

            GraphQLMutation mutation = AnnotationUtils.findAnnotation(method, GraphQLMutation.class);
            if (mutation != null) {
                List<ResolverParameter> resolverParams = checkParameters(method);
                builder = wireQuery(builder, mutation.type(), mutation.field(), dataFetchingEnvironment ->
                        method.invoke(resolver, buildInvokeParameters(dataFetchingEnvironment, resolverParams)));
            }
        }
        return builder;
    }

    private List<ResolverParameter> checkParameters(Method method) throws Exception {
        List<ResolverParameter> rp = Lists.newArrayList();
        for (Parameter p: method.getParameters()) {
            GraphQLArgument arg = AnnotationUtils.findAnnotation(p, GraphQLArgument.class);
            if (arg != null) {
                ArgumentParameter a = new ArgumentParameter();
                String name = arg.name();
                if (name.equals("")) {
                    name = arg.value();
                }
                if (name.equals(""))
                    throw new RuntimeException("missing argument name on @GraphQLArgument");
                a.setName(arg.name());
                a.setParameter(p);
                a.setAnnotation(arg);
                rp.add(a);
                continue;
            }

            GraphQLSource src = AnnotationUtils.findAnnotation(p, GraphQLSource.class);
            if (src != null) {
                SourceParameter s = new SourceParameter();
                s.setAnnotation(src);
                s.setParameter(p);
                rp.add(s);
                continue;
            }

            GraphQLPath path = AnnotationUtils.findAnnotation(p, GraphQLPath.class);
            if (path != null) {
                PathParameter pth = new PathParameter();
                pth.setParameter(p);
                pth.setAnnotation(path);
                if (p.getType().equals(String.class)) {
                } else if (p.getType().equals(Integer.class) || p.getType().equals(int.class)) {
                    pth.setAsLevel(true);
                } else {
                    throw new RuntimeException("illegal argument type for @GraphQLPath, should be a String or an integer(as path level)");
                }
                rp.add(pth);
                continue;
            }

            GraphQLContext ctx = AnnotationUtils.findAnnotation(p, GraphQLContext.class);
            if (ctx != null) {
                ContextParameter c = new ContextParameter();
                c.setName(ctx.key());
                c.setParameter(p);
                c.setAnnotation(ctx);
                rp.add(c);
                continue;
            }

            GraphQLRequireAnyOfFields any = AnnotationUtils.findAnnotation(p, GraphQLRequireAnyOfFields.class);
            if (any != null) {
                if (!p.getType().equals(Boolean.class) && !p.getType().equals(boolean.class)) {
                    throw new RuntimeException("illegal argument type for @GraphQLRequireAnyOfFields, should be a Boolean");
                }
                RequireAnyOfFieldsParameter a = new RequireAnyOfFieldsParameter(any.value());
                a.setAnnotation(any);
                a.setParameter(p);
                rp.add(a);
                continue;
            }

            GraphQLRequireAllOfFields all = AnnotationUtils.findAnnotation(p, GraphQLRequireAllOfFields.class);
            if (all != null) {
                if (!p.getType().equals(Boolean.class) && !p.getType().equals(boolean.class)) {
                    throw new RuntimeException("illegal argument type for @GraphQLRequireAllOfFields, should be a Boolean");
                }
                RequireAllOfFieldsParameter a = new RequireAllOfFieldsParameter(any.value());
                a.setParameter(p);
                a.setAnnotation(all);
                rp.add(a);
                continue;
            }

            if (p.getType().equals(DataFetchingEnvironment.class)) {
                EnvParameter e = new EnvParameter();
                e.setParameter(p);
                rp.add(e);
                continue;
            }

            throw new IllegalArgumentException("unsupported argument");
        }

        return rp;
    }


    private Object[] buildInvokeParameters(DataFetchingEnvironment dataFetchingEnvironment, List<ResolverParameter> parameters) {
        List<Object> arguments = Lists.newArrayList();
        for (ResolverParameter p : parameters) {
            if (p instanceof ArgumentParameter) {
                Object a = dataFetchingEnvironment.getArgument(((ArgumentParameter) p).getName());
                arguments.add(a);
            } else if (p instanceof SourceParameter) {
                arguments.add(dataFetchingEnvironment.getSource());
            } else if (p instanceof EnvParameter) {
                arguments.add(dataFetchingEnvironment);
            } else if (p instanceof ContextParameter) {
                Object context = dataFetchingEnvironment.getContext();
                ContextParameter ctx = (ContextParameter) p;
                if (ctx.hasContextKey()) {
                    arguments.add(context);
                } else {
                    if (context instanceof Map) {
                        arguments.add(((Map) context).get(ctx.getName()));
                    }
                }
            } else if (p instanceof PathParameter) {
                ExecutionPath path = dataFetchingEnvironment.getExecutionStepInfo().getPath();
                if (((PathParameter) p).isAsLevel()) {
                    arguments.add(path.getLevel());
                } else {
                    arguments.add(path.toString());
                }
            } else if (p instanceof RequireAnyOfFieldsParameter) {
                RequireAnyOfFieldsParameter any = (RequireAnyOfFieldsParameter) p;
                arguments.add(dataFetchingEnvironment.getSelectionSet().containsAnyOf(any.getGlob(), any.getGlobs()));
            } else if (p instanceof RequireAllOfFieldsParameter) {
                RequireAllOfFieldsParameter all = (RequireAllOfFieldsParameter) p;
                arguments.add(dataFetchingEnvironment.getSelectionSet().containsAllOf(all.getGlob(), all.getGlobs()));
            } else {
                throw new RuntimeException("unknown argument");
            }
        }
        return arguments.toArray();
    }
}

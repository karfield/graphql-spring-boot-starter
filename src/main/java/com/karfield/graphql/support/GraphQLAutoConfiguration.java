package com.karfield.graphql.support;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
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
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

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

    @PostConstruct
    public void init() throws IOException {
        EnableGraphQL config = getGraphQLConfig();
        if (config == null) {
            return;
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
                String name = query.ann.type();
                if (name.equals("")) {
                    name = "Query";
                }
                builder = builder.type(newTypeWiring(name).dataFetcher(query.ann.field(), (DataFetcher) query.instance));
            }
        }

        List<WiringPair<GraphQLMutation>> mutations = scanWirings(GraphQLMutation.class);
        for (WiringPair<GraphQLMutation> mutation: mutations) {
            if (mutation.instance instanceof DataFetcher) {
                String name = mutation.ann.type();
                if (name.equals("")) {
                    name = "Mutation";
                }
                builder = builder.type(newTypeWiring(name).dataFetcher(mutation.ann.field(), (DataFetcher) mutation.instance));
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

        return builder.build();
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
}

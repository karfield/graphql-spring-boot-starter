package com.karfield.graphql.resolvers;

import com.karfield.graphql.annotations.GraphQLQuery;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;

@GraphQLQuery(field = "a")
public class AFetcher implements DataFetcher {
    @Override
    public Object get(DataFetchingEnvironment dataFetchingEnvironment) throws Exception {
        return null;
    }
}

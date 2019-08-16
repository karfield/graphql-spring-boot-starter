package com.karfield.graphql.support.parameters;

import lombok.Data;

@Data
public class ContextParameter extends Base {
    private String name;

    public boolean hasContextKey() {
        return !name.equals("");
    }
}

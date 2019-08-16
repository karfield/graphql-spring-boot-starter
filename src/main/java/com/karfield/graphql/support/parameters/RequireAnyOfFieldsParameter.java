package com.karfield.graphql.support.parameters;

import com.google.common.collect.Lists;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RequireAnyOfFieldsParameter extends Base {
    private String glob;
    private String[] globs;

    public RequireAnyOfFieldsParameter(String[] gs) {
        if (gs.length == 0)
            throw new IllegalArgumentException("none fields");
        glob = gs[0];
        ArrayList<String> l = Lists.newArrayList(gs);
        if (l.size() > 1) {
            List<String> sl = l.subList(1, l.size() - 1);
            globs = sl.toArray(new String[0]);
        } else {
            globs = new String[0];
        }
    }
}

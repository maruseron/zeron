package com.maruseron.zeron.fixtures;

import java.util.ArrayList;
import java.util.List;

public final class JavaWildcardFixture {
    public static final List<? extends String> upper = List.of("upper");
    public static final List<? super String> lower = new ArrayList<Object>();
    public static final List<?> unbounded = List.of("unbounded");

    private JavaWildcardFixture() {}
}

package io.github.samsonllam.weather.support;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Renders a throwable the way a logger would, cause chain included, so tests can check what would end up in logs. */
public final class StackTraces {

    private StackTraces() {
    }

    public static String of(Throwable throwable) {
        StringWriter out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}

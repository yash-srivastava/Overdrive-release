package com.overdrive.app.server;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact admission registry for diagnostics available in DiLink 5 mode. */
public final class DiLink5SdkDiagnosticRegistry {
    public enum Operation {
        STATUS,
        TYPED_INTEGER_READ,
        TYPED_FLOAT_READ,
        SDK_GETTER,
        SDK_CONTROL,
        SDK_DISCOVERY
    }

    private static final Set<String> ALLOWED_ENDPOINTS;
    private static final Set<String> REVIEWED_REFLECTIVE_CALLS = Collections.emptySet();

    static {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add("/api/debug/autoservice/status");
        endpoints.add("/api/debug/autoservice/typed-int");
        endpoints.add("/api/debug/autoservice/typed-float");
        ALLOWED_ENDPOINTS = Collections.unmodifiableSet(endpoints);
    }

    private DiLink5SdkDiagnosticRegistry() {}

    public static boolean isEndpointAllowed(String path) {
        return path != null && ALLOWED_ENDPOINTS.contains(path);
    }

    /**
     * Reflective SDK diagnostics remain denied until an exact class, method,
     * signature, and read/write classification has been reviewed and registered.
     */
    public static boolean isReflectiveCallAllowed(
            Operation operation, String className, String methodName, Class<?>... parameterTypes) {
        if (operation == null || className == null || methodName == null) return false;
        StringBuilder key = new StringBuilder(operation.name())
                .append(':').append(className).append('#').append(methodName).append('(');
        if (parameterTypes != null) {
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) key.append(',');
                key.append(parameterTypes[i] == null ? "null" : parameterTypes[i].getName());
            }
        }
        return REVIEWED_REFLECTIVE_CALLS.contains(key.append(')').toString());
    }
}

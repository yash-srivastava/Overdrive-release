package com.ts.lib.caradapter;

import android.content.Context;
import java.util.HashMap;
import java.util.Map;

/** Test-only verified CarAdapterManager API from ts-framework.jar. */
public final class CarAdapterManager {
    public static CarAdapterManager testInstance;

    public boolean bound = true;
    public int connectCalls;
    public Runnable onConnect;
    private final Map<String, Object> adapters = new HashMap<>();

    public static CarAdapterManager getInstance(Context context) {
        return testInstance;
    }

    public boolean isCarServiceBound() {
        return bound;
    }

    public void connect() {
        connectCalls++;
        if (onConnect != null) onConnect.run();
    }

    public void putAdapter(String key, Object adapter) {
        adapters.put(key, adapter);
    }

    public Object getCarAdapterManager(String key) {
        return adapters.get(key);
    }
}

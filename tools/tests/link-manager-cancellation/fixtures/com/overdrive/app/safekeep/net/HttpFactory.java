package com.overdrive.app.safekeep.net;

import com.overdrive.app.safekeep.LinkManagerCancellationTest;
import okhttp3.OkHttpClient;

/** Only supplies the interceptor-backed real client. */
public final class HttpFactory {
    public enum ProxyMode { AUTO; public static ProxyMode parse(String value) { return AUTO; } }
    public static OkHttpClient client(ProxyMode mode, String certSha256) {
        return LinkManagerCancellationTest.client();
    }
}

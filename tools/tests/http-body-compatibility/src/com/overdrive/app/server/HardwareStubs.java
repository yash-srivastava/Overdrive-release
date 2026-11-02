package com.overdrive.app.server;

import java.io.OutputStream;
import java.net.Socket;

// Compiling the whole production handler preserves every branch; unrelated branches fail closed.
class GenAiVoiceWebSocket {
    static final String PATH = "/ws/genai";
    static void handle(Socket socket, String key, String language, String microphone) { throw new AssertionError(); }
}
class GenAiChatWebSocket {
    static final String PATH = "/ws/genai/chat";
    static void handle(Socket socket, String key) { throw new AssertionError(); }
}
class RemoteDevViewWebSocketStream {
    static final String PATH = "/ws/dev-view";
    static String sessionFromProtocols(String protocols) { throw new AssertionError(); }
    static void handle(Socket socket, String key, String session) { throw new AssertionError(); }
}
class RemoteDevViewApiHandler {
    static boolean validateStreamSession(String session) { throw new AssertionError(); }
}
class RemoteCommunicationWebSocket {
    static void handle(Socket socket, String key) { throw new AssertionError(); }
}
class CabinAudioWebSocket {
    static void handle(Socket socket, String key) { throw new AssertionError(); }
}
class GenAiApiHandler {
    static String queryParam(String path, String name) { throw new AssertionError(); }
}
class AuthApiHandler {
    static void handle(String method, String path, String body, OutputStream out,
                       String identity, String ip) { throw new AssertionError(); }
}
class LocaleManager {
    static final String[] SUPPORTED = {"en"};
    static void fromAcceptLanguage(String language) {}
    static String get() { return "en"; }
    static String set(String language) { return "en"; }
    static boolean isSupported(String language) { return "en".equals(language); }
}

package com.overdrive.app.server;

import java.io.OutputStream;
import java.net.Socket;

final class Admission { static String websocket; }
class GenAiVoiceWebSocket {
    static final String PATH = "/ws/genai";
    static void handle(Socket socket, String key, String lang, String mic) { Admission.websocket = "genai"; }
}
class GenAiChatWebSocket {
    static final String PATH = "/ws/genai/chat";
    static void handle(Socket socket, String key) { Admission.websocket = "genai-chat"; }
}
class RemoteDevViewWebSocketStream {
    static final String PATH = "/ws/dev-view";
    static String sessionFromProtocols(String protocols) { return null; }
    static void handle(Socket socket, String key, String session) { throw new AssertionError(); }
}
class RemoteDevViewApiHandler { static boolean validateStreamSession(String session) { return false; } }
class RemoteCommunicationWebSocket {
    static void handle(Socket socket, String key) { Admission.websocket = "communicate"; }
}
class CabinAudioWebSocket {
    static void handle(Socket socket, String key) { Admission.websocket = "cabin-audio"; }
}
class GenAiApiHandler { static String queryParam(String path, String name) { return null; } }
class AuthApiHandler {
    static void handle(String method, String path, String body, OutputStream out, String caller, String ip) {
        throw new AssertionError("Auth endpoint not part of this fixture");
    }
}
class LocaleManager {
    static final String[] SUPPORTED = {"en"};
    static void fromAcceptLanguage(String value) {}
    static String get() { return "en"; }
    static String set(String value) { return "en"; }
    static boolean isSupported(String value) { return "en".equals(value); }
}

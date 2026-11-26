package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.TextInterpolator;
import com.overdrive.app.automation.type.DynamicColourType;
import com.overdrive.app.automation.type.DynamicIntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.server.HttpServer;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiAction extends DeclaredAction {
    private static final String TYPE = "api";
    // Regex to match ${variable}
    private static final Pattern p = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d{1,9}(?:\\.0+)?");

    private final String method;
    private final String path;
    private final String body;

    /**
     * An action to send an API event
     * The variables passed in to this will replace placeholders inside the path and body
     * This allows a path like /api/delete/${id} or a body like { "id": "${id}" }
     * Ensure the body can still be parsed as JSON after the variable replacement
     *
     * @param label       The label for this notification with an id and display name
     * @param description The description for this action
     * @param method      The HTTP method e.g. POST or GET
     * @param path        The HTTP endpoint
     * @param body        The HTTP body. Would usually need to be valid JSON
     * @param variables   The variables to concatenate for the payload
     */
    public ApiAction(Label label, String description, String method, String path, String body, Type... variables) {
        super(TYPE, label, description, List.of(variables));
        this.method = method;
        this.path = path;
        this.body = body;
    }

    /**
     * The HTTP method that was stored for this action
     * Does not need to be public as it is only used for the trigger
     *
     * @return The HTTP method that was stored for this action
     */
    private String getMethod() {
        return method;
    }

    /**
     * The HTTP endpoint that was stored for this action
     * Does not need to be public as it is only used for the trigger
     *
     * @return The HTTP endpoint that was stored for this action
     */
    private String getPath() {
        return path;
    }

    /**
     * The HTTP body that was stored for this action
     * Does not need to be public as it is only used for the trigger
     *
     * @return The HTTP body that was stored for this action
     */
    private String getBody() {
        return body;
    }

    /**
     * Trigger a vehicle control action
     * The variables stored will be concatenated and sent as the control payload
     * <p>
     * This method needs to be updated to implement the sub variable to allow more vehicle controls
     *
     * @param automationAction The AutomationAction with the variables needed to trigger this action
     */
    public void trigger(AutomationAction automationAction) {
        triggerWithResult(automationAction);
    }

    @Override
    public boolean triggerWithResult(AutomationAction automationAction) {
        // Path substitution is NOT JSON-escaped (it's a URL, and existing values are
        // simple tokens/ints); body substitution IS JSON-escaped so a free-text value
        // (e.g. the Speak message with a quote/backslash/newline) can't break the JSON.
        String path = replaceVariables(getPath(), automationAction.getVariables(), false);
        String body = replaceVariables(getBody(), automationAction.getVariables(), true);
        HttpServer server = CameraDaemon.getHttpServer();
        if (server != null && path != null && body != null) {
            String response = server.automationApiRequest(getMethod(), path, body);
            boolean success = responseSucceeded(response);
            logFailure(path, response, success);
            return success;
        } else {
            logger.error("Could not trigger API action (" + getMethod() + "," + path + "," + body + ")");
            return false;
        }
    }

    /**
     * Interpret the in-process HTTP response for queue/result semantics. A routed 2xx response is
     * successful unless its JSON body explicitly says otherwise. Async {@code starting:true}
     * responses are accepted because the command was admitted and intentionally completes later.
     */
    static boolean responseSucceeded(String response) {
        if (response == null) return false;
        String trimmed = response.trim();
        if (trimmed.startsWith("HTTP/")) {
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace < 0 || trimmed.length() < firstSpace + 4) return false;
            try {
                int status = Integer.parseInt(trimmed.substring(firstSpace + 1, firstSpace + 4));
                if (status < 200 || status >= 300) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        int start = response.indexOf('{');
        if (start < 0) return true;
        try {
            org.json.JSONObject json = new org.json.JSONObject(response.substring(start));
            if (json.optBoolean("starting", false)) return true;
            if (json.has("success") && !json.optBoolean("success", false)) return false;
            String status = json.optString("status", "");
            return !"error".equalsIgnoreCase(status)
                    && !"failed".equalsIgnoreCase(status)
                    && !"failure".equalsIgnoreCase(status);
        } catch (Exception e) {
            // A 2xx handler is allowed to return a non-JSON body.
            return true;
        }
    }

    /**
     * Log an API action that did not succeed. A null response means it never ran (see below); a
     * {@code "success":false} body means the endpoint ran and declined. Anything else — a
     * non-JSON body, no {@code success} key, or an async {@code "starting":true} — is left
     * alone, so this never turns a working action into a scary log line.
     */
    private void logFailure(String path, String response, boolean success) {
        if (success) return;
        if (response == null) {
            // null covers three cases in automationApiRequest — allowlist refusal, no handler
            // matched the path, and a handler that threw — so don't name just one of them.
            logger.warn("API action " + getMethod() + " " + path + " was not executed"
                    + " (allowlist refusal, unrouted path, or handler error)");
            return;
        }
        try {
            // The in-process call returns the body only, but be tolerant of a leading
            // preamble by starting at the first brace.
            int start = response.indexOf('{');
            if (start < 0) {
                logger.warn("API action " + getMethod() + " " + path + " failed");
                return;
            }
            org.json.JSONObject json = new org.json.JSONObject(response.substring(start));
            logger.warn("API action " + getMethod() + " " + path + " failed: "
                    + json.optString("error",
                    json.optString("message", json.optString("status", "(no reason given)"))));
        } catch (Exception e) {
            logger.warn("API action " + getMethod() + " " + path + " failed");
        }
    }

    /**
     * Replace any {@code ${variable}} in the input with its value from the map.
     *
     * @param input      The String to replace the variables for
     * @param variables  A map of String → a class whose toString() gives the value
     * @param jsonEscape When true, each substituted value is JSON-string-escaped so it
     *                   is safe to place inside a JSON body between quotes. Numeric
     *                   values (e.g. {@code "value":${level}}) are written WITHOUT
     *                   quotes in the template, so escaping a bare number is a no-op
     *                   (digits need no escaping) — only genuine free text is affected.
     * @return The string with variables replaced, or null on error
     */
    private String replaceVariables(String input, Map<String, Object> variables, boolean jsonEscape) {
        try {
            Matcher m = p.matcher(input);

            StringBuffer result = new StringBuffer();
            while (m.find()) {
                String key = m.group(1);
                String value;
                if (variables.containsKey(key)) {
                    // This action's own parameter (the normal case). Its VALUE may itself
                    // carry references — a user typing "SOC ${signal:batteryLevel}%" into
                    // the toast/dialog/speak message field — so resolve those too. The
                    // action's own template placeholders are already consumed by this pass,
                    // so a substituted value can never re-enter it.
                    value = TextInterpolator.interpolate(variables.get(key).toString());
                    // Spliced unquoted, and variables are MQTT-writable: only a plain whole number may pass.
                    if (isDynamicNumber(key)) {
                        String number = wholeNumber(value);
                        if (number == null) {
                            logger.warn("API action " + getLabel().getId() + ": " + key
                                    + " is not a whole number ('" + value + "'); not sent");
                            return null;
                        }
                        value = number;
                    }
                } else {
                    // Not one of this action's parameters: resolve it as a state reference —
                    // ${var:NAME}, ${signal:TYPE[:k=v]}, or the bare ${NAME} variable
                    // shorthand this path has always accepted. Absent → keep the literal
                    // placeholder (unchanged legacy behaviour).
                    String stateVal = TextInterpolator.resolve(m.group(0));
                    value = (stateVal != null) ? stateVal : m.group(0);
                }
                if (jsonEscape) value = jsonEscape(value);
                m.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            m.appendTail(result);

            return result.toString();
        } catch (Exception e) {
            logger.error("Failed to replace variables for automation", e);
            return null;
        }
    }

    /** True when {@code key} is a numeric parameter that may hold a {@code ${…}} reference. */
    private boolean isDynamicNumber(String key) {
        for (Type type : getVariables()) {
            if (key.equals(type.getLabel().getId())) {
                return type instanceof DynamicIntType || type instanceof DynamicColourType;
            }
        }
        return false;
    }

    /** Canonical integer text for a plain whole number ("7", " 7 ", "7.0"), else null. */
    static String wholeNumber(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!WHOLE_NUMBER.matcher(s).matches()) return null;
        int dot = s.indexOf('.');
        return Integer.toString(Integer.parseInt(dot < 0 ? s : s.substring(0, dot)));
    }

    /**
     * Escape a string for safe inclusion inside a JSON string literal (the surrounding
     * quotes are already in the body template). Handles the JSON control set: backslash,
     * double-quote, and the C0 whitespace/control chars. This is what keeps a Speak
     * message like {@code He said "go"} or a value with a newline from producing a
     * malformed body that the daemon's JSONObject parse would reject.
     */
    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}

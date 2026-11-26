package com.overdrive.app.community;

import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Both submission routes must reject invalid input before contacting the provider. */
public class CommunitySubmissionValidationTest {
    @BeforeClass
    public static void muteAndroidLog() {
        DaemonLogger.Config cfg = new DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        DaemonLogger.configure(cfg);
    }

    @Test
    public void malformedRulesAreRejectedByBothRoutes() throws Exception {
        assertRejectedByBoth(new JSONObject().put("rules", new JSONObject()),
                400, "automation does not match the schema");
    }

    @Test
    public void nameAndDescriptionAreRequiredByBothRoutes() throws Exception {
        assertRejectedByBoth(submission().put("name", "  "), 400, "name required");
        assertRejectedByBoth(submission().put("description", "  "), 400, "description required");
    }

    @Test
    public void shellActionsAreRejectedByBothRoutes() throws Exception {
        JSONObject request = submission();
        request.getJSONObject("rules").put("actions", new JSONArray().put(shellAction()));
        assertRejectedByBoth(request, 403, "shell automations cannot be shared to the community catalog");
    }

    @Test
    public void bundledShellActionsAreRejectedByBothRoutes() throws Exception {
        JSONObject request = submission().put("actionGroups", new JSONObject().put("group",
                new JSONObject().put("name", "Group").put("actions", new JSONArray().put(shellAction()))));
        assertRejectedByBoth(request, 403, "an action group cannot contain a shell action");
    }

    private static JSONObject shellAction() throws Exception {
        return new JSONObject().put("type", "shell")
                .put("variables", new JSONObject().put("command", "true"));
    }

    private static JSONObject submission() throws Exception {
        JSONObject rules = new JSONObject()
                .put("triggers", new JSONArray().put(new JSONObject()
                        .put("type", "callState").put("variables", new JSONObject())))
                .put("conditions", new JSONArray()).put("delay", 0)
                .put("actions", new JSONArray().put(new JSONObject().put("type", "setVariable")
                        .put("variables", new JSONObject().put("name", "community_test").put("value", "1"))));
        // No localId: skip the publish mapping lookup. All cases fail before author persistence.
        return new JSONObject().put("rules", rules).put("name", "Example")
                .put("description", "Example description").put("authorName", "Test author");
    }

    private static void assertRejectedByBoth(JSONObject request, int status, String message) throws Exception {
        assertRejected("POST", "/api/community/publish", request, status, message);
        assertRejected("PUT", "/api/community/automation/example", request, status, message);
    }

    private static void assertRejected(String method, String path, JSONObject request,
                                       int status, String message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(CommunityApiHandler.handle(method, path, request.toString(), out));
        String response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(response, response.startsWith("HTTP/1.1 " + status + " "));
        int bodyStart = response.indexOf("\r\n\r\n");
        assertTrue(bodyStart >= 0);
        JSONObject body = new JSONObject(response.substring(bodyStart + 4));
        assertFalse(body.getBoolean("success"));
        assertEquals(message, body.getString("error"));
    }
}

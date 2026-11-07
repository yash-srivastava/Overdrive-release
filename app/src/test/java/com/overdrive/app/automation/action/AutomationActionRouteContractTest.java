package com.overdrive.app.automation.action;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.mqtt.VehicleControlCatalog;
import com.overdrive.app.server.HttpServer;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ensures every editor-visible routed action still has an executable backend. */
public class AutomationActionRouteContractTest {

    @Test
    public void everyApiActionUsesTheAutomationAllowlist() throws Exception {
        Actions actions = new Actions();
        Field actionsField = Actions.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        Field pathField = ApiAction.class.getDeclaredField("path");
        pathField.setAccessible(true);
        Method allowed = HttpServer.class.getDeclaredMethod("isAutomationAllowed", String.class);
        allowed.setAccessible(true);

        for (Action action : actionMap(actionsField, actions).values()) {
            if (!(action instanceof ApiAction)) continue;
            String path = (String) pathField.get(action);
            boolean isAllowed = (Boolean) allowed.invoke(null, path);
            // SECURITY (V60-08): vehicle actuator endpoints are deliberately NOT
            // automation-reachable anymore (MQTT-triggered automations are not an
            // authenticated principal). Everything else must stay allowed.
            if (path.startsWith("/api/vehicle/")) {
                assertFalse(action.getLabel().getId() + " must NOT be automation-reachable: " + path, isAllowed);
            } else {
                assertTrue(action.getLabel().getId() + " is outside the automation allowlist: " + path, isAllowed);
            }
        }
    }

    @Test
    public void surveillanceAutomationCannotReachDeterrentAssetOrPreviewRoutes()
            throws Exception {
        Method allowed = HttpServer.class.getDeclaredMethod(
                "isAutomationAllowed", String.class);
        allowed.setAccessible(true);

        assertTrue((Boolean) allowed.invoke(null, "/api/surveillance/enable"));
        assertTrue((Boolean) allowed.invoke(null, "/api/surveillance/disable"));
        assertTrue((Boolean) allowed.invoke(null, "/api/surveillance/config"));
        assertFalse((Boolean) allowed.invoke(
                null, "/api/surveillance/screen-deterrent/image"));
        assertFalse((Boolean) allowed.invoke(
                null, "/api/surveillance/screen-deterrent/test"));
    }

    @Test
    public void everyVehicleControlActionResolvesToCatalogEntities() throws Exception {
        Actions actions = new Actions();
        Field actionsField = Actions.class.getDeclaredField("actions");
        actionsField.setAccessible(true);

        for (Action action : actionMap(actionsField, actions).values()) {
            if (!(action instanceof VehicleControlAction)) continue;
            VehicleControlAction control = (VehicleControlAction) action;
            List<List<String>> suffixParts = new ArrayList<>();
            for (Type variable : control.getVariables()) {
                if ("payload".equals(variable.getLabel().getId())) continue;
                assertTrue(control.getLabel().getId() + " has a non-enum catalog suffix",
                        variable instanceof EnumType);
                List<String> values = new ArrayList<>();
                for (Label option : ((EnumType) variable).getOptions()) {
                    values.add(option.getId());
                }
                suffixParts.add(values);
            }

            if (suffixParts.isEmpty()) {
                assertCatalogEntity(control.getLabel().getId());
            } else {
                assertSuffixProducts(control.getLabel().getId(), suffixParts, 0,
                        new ArrayList<>());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Action> actionMap(Field field, Actions actions)
            throws IllegalAccessException {
        return (Map<String, Action>) field.get(actions);
    }

    private static void assertSuffixProducts(
            String base, List<List<String>> parts, int index, List<String> selected) {
        if (index == parts.size()) {
            assertCatalogEntity(base + "_" + String.join("_", selected));
            return;
        }
        for (String value : parts.get(index)) {
            selected.add(value);
            assertSuffixProducts(base, parts, index + 1, selected);
            selected.remove(selected.size() - 1);
        }
    }

    private static void assertCatalogEntity(String id) {
        assertNotNull("Automation action has no VehicleControlCatalog entity: " + id,
                VehicleControlCatalog.get(id));
    }
}

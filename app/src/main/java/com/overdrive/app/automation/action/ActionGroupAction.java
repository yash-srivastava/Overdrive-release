package com.overdrive.app.automation.action;

import com.overdrive.app.automation.ActionGroups;
import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.type.ActionGroupType;
import com.overdrive.app.automation.value.Label;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs a reusable {@link ActionGroups action group} by id — call-by-reference, so
 * editing the group changes every automation that invokes it. The group's actions run
 * through {@link Automations#runActionList}, so they're depth-guarded and can contain
 * anything (including loops / ifs) that a normal action list can.
 *
 * <p><b>Cycle guard.</b> A group can invoke another group; without protection, group A
 * → group B → group A would recurse forever. A per-thread stack of the group ids
 * currently executing short-circuits any id already on the stack, and the
 * {@link Automations#runActionList} depth cap is the second backstop.
 *
 * <p>The {@code groupId} is picked from a live list (the group picker); it's stored as a
 * plain string (a UUID, so a StringType — not the package-charset AppType — is used).
 * The web renders a live group picker for this action id.
 */
public class ActionGroupAction extends DeclaredAction {
    private static final String TYPE = "actionGroup";

    // Per-thread set of group ids currently on the execution stack (cycle detection).
    // Actions run on the single AutomationQueue worker (and the /test executor); each
    // independent run starts empty.
    private static final ThreadLocal<Set<String>> STACK = ThreadLocal.withInitial(HashSet::new);

    public ActionGroupAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new ActionGroupType(new Label("groupId", "automation.action_group"))));
    }

    public void trigger(AutomationAction automationAction) {
        Object gidObj = automationAction.getVariables().get("groupId");
        String gid = gidObj == null ? null : gidObj.toString().trim();
        if (gid == null || gid.isEmpty()) {
            logger.warn("ActionGroupAction: no groupId, skipping");
            return;
        }
        // A queued run resuming after a yielded wait replays the list it started, even if
        // the group was edited or deleted meanwhile.
        Object recorded = Automations.queuedFlowRecord(automationAction);
        List<AutomationAction> actions;
        if (recorded instanceof List) {
            @SuppressWarnings("unchecked")
            List<AutomationAction> started = (List<AutomationAction>) recorded;
            actions = started;
        } else {
            if (!ActionGroups.exists(gid)) {
                logger.warn("ActionGroupAction: unknown group " + gid + ", skipping");
                return;
            }
            actions = ActionGroups.getActions(gid);
        }
        Set<String> stack = STACK.get();
        if (!stack.add(gid)) {
            // Already executing this group higher up the stack → cycle. Stop.
            logger.warn("ActionGroupAction: cycle detected on group " + gid + ", skipping");
            return;
        }
        Automations.recordQueuedFlow(automationAction, actions);
        try {
            Automations.runActionList(actions);
        } finally {
            stack.remove(gid);
        }
    }
}

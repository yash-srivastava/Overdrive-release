package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.ExpressionEvaluator;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.config.UnifiedConfigManager;

import java.util.List;

/**
 * Automation action that runs a shell command in the daemon (UID 2000).
 *
 * <p>This is the most powerful — and most dangerous — action, because automations
 * fire <b>autonomously</b> on vehicle events (not a deliberate button press like
 * the key-mapping shell hatch). A misfiring automation could run its command
 * repeatedly on its own. So it is gated behind a DEDICATED opt-in flag,
 * {@code automation.allowShell} (toggle on the Automations page), SEPARATE from
 * the key-mapping "Advanced actions" toggle ({@code keymap.allowAdvanced}) —
 * enabling shell for a hand-pressed key must NOT silently also arm autonomous
 * shell in automations.
 * When the flag is off, {@link #trigger} is a logged no-op; the command never
 * executes.
 *
 * <p>Runs in the daemon process, the same context {@code Automations.update}
 * evaluates in, so {@code Runtime.exec} has the daemon's privileges — the same
 * path {@code KeymapApiHandler} uses for the key-mapping shell action.
 *
 * <p>The command supports {@code ${var:NAME}} / {@code ${signal:TYPE}} substitution,
 * restricted to NUMERIC values — see {@link #interpolateNumeric}. That restriction is a
 * security boundary, not a convenience: variables are externally writable (an MQTT broker
 * can push arbitrary text into automation state via
 * {@code Automations.publishMqttTrigger}), and this string is handed to {@code sh -c} as
 * UID 2000. Substituting free text would let a broker inject shell metacharacters into a
 * privileged command line. For arithmetic, compute it in a Calculate Variable action and
 * reference the result — the shell's own {@code $((…))} is left to the shell.
 */
public class ShellAction extends DeclaredAction {
    private static final String TYPE = "shell";

    // The only two token forms this action substitutes. Everything else that looks like
    // shell expansion (${HOME}, $((1+2)), $(cmd), $VAR) is left for sh, so a command
    // written before this feature existed behaves identically.
    private static final String VAR_PREFIX = "${var:";
    private static final String SIGNAL_PREFIX = "${signal:";

    public ShellAction(Label label, String description) {
        // Single free-text variable: the command line. 500 chars is generous for a
        // one-liner while bounding a pathological paste. Carries a warning key so the
        // form renders the same amber "runs unattended" caution as the key-mapping
        // shell field.
        super(TYPE, label, description, List.of(
                new StringType(new Label("command", "automation.shell_command"), 500, "automation.shell_warning")));
    }

    /**
     * Run the bound command — but only if the dedicated automation-shell gate is
     * on. The gate is re-checked here at fire time (not just in the UI), so a
     * binding saved while enabled cannot keep firing after the user turns the
     * flag back off.
     */
    public void trigger(AutomationAction automationAction) {
        Object cmdObj = automationAction.getVariables().get("command");
        String cmd = cmdObj == null ? "" : cmdObj.toString().trim();
        if (cmd.isEmpty()) {
            logger.warn("ShellAction: empty command, skipping");
            return;
        }
        // Substitute state references BEFORE the gate check, so a command whose values
        // can't be resolved is refused without consulting the gate at all. Only our own
        // ${var:…}/${signal:…} tokens are touched — see interpolateNumeric.
        if (cmd.indexOf(VAR_PREFIX) >= 0 || cmd.indexOf(SIGNAL_PREFIX) >= 0) {
            String resolved = interpolateNumeric(cmd);
            if (resolved == null) {
                // Never fall through to the raw template: running `input tap ${…} 400`
                // verbatim would either error or, worse, tap a wrong coordinate.
                logger.warn("ShellAction: refusing to run '" + cmd
                        + "' — a substitution did not resolve to a number");
                return;
            }
            cmd = resolved;
        }
        if (!UnifiedConfigManager.isAutomationShellAllowed()) {
            // Gate off → never execute. Logged so a silently-inert automation is
            // debuggable ("why didn't my shell automation run?").
            logger.warn("ShellAction: automation shell is disabled (automation.allowShell=false); "
                    + "refusing to run: " + cmd);
            return;
        }
        try {
            // Mirror KeymapApiHandler.runShell: drain (and discard) combined
            // stdout+stderr on a daemon thread so the child can never wedge on a
            // full (>~64KB) pipe, bound the whole exec by a 5s ceiling and
            // force-kill a long-lived-stdout command (logcat, top, tail -f) so it
            // cannot park the AutomationQueue worker, then reap the child so
            // nothing accumulates across repeated autonomous fires.
            final Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd))
                    .redirectErrorStream(true)
                    .start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    while (is.read(buf) != -1) { /* discard */ }
                } catch (Throwable ignored) { /* pipe closed on kill */ }
            }, "automation-shell-drain");
            drain.setDaemon(true);
            drain.start();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                logger.info("ShellAction fired: " + cmd);
            } catch (InterruptedException ie) {
                // The AutomationQueue worker is being torn down (e.g. disable-all).
                // Kill the child and RE-ASSERT the interrupt so the worker's blocking
                // take() throws and the thread exits as designed — swallowing it here
                // would clear the flag and turn the worker into an unkillable orphan.
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            } finally {
                // Idempotent: guarantees the child + its fds are reaped whether
                // waitFor returned, timed out, or was interrupted — no orphan on
                // any path.
                p.destroyForcibly();
                try {
                    drain.join(500);    // let the reader unwind after the pipe closes
                } catch (InterruptedException je) {
                    Thread.currentThread().interrupt();
                }
                try { is.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            logger.warn("ShellAction failed: " + t.getMessage());
        }
    }

    /**
     * Replace every {@code ${var:…}} / {@code ${signal:…}} reference with its NUMERIC
     * value, or return null if any of them fails to resolve to a number.
     *
     * <p>Numeric-only is the whole safety argument. The output of each substitution is
     * re-verified to contain nothing but {@code [-0-9.]} before it is spliced in, so no
     * value — however it got into automation state — can introduce a quote, semicolon,
     * backtick, {@code $}, or whitespace into a command line running as UID 2000. A
     * non-numeric variable (a mode word like "sport") is a refusal, not a silent skip:
     * an all-or-nothing result means a partially-substituted command never reaches sh.
     *
     * <p><b>Only OUR OWN two prefixes are touched</b> — {@code ${var:} and ${signal:}.
     * {@code ${…}} and {@code $((…))} are also SHELL syntax, so treating every occurrence
     * as ours would hijack commands that already work: {@code echo ${HOME}} would be
     * refused (not our grammar → unresolvable), and {@code $((1000/3))} would be computed
     * in floating point (333.333…) where sh yields the integer 333. Both are pre-existing,
     * already-shipped shell commands, so anything outside our two prefixes is passed
     * through untouched for sh to expand exactly as before. To do arithmetic on vehicle
     * state, use the Calculate Variable action and reference the result as
     * {@code ${var:NAME}} — that keeps one expression engine instead of a second dialect
     * shadowing the shell's.
     */
    static String interpolateNumeric(String input) {
        if (input == null) return null;
        try {
            StringBuilder out = new StringBuilder(input.length() + 16);
            int i = 0;
            while (i < input.length()) {
                int nameAt = input.startsWith(VAR_PREFIX, i) ? i + VAR_PREFIX.length()
                           : input.startsWith(SIGNAL_PREFIX, i) ? i + SIGNAL_PREFIX.length()
                           : -1;
                // A shell parameter expansion whose parameter happens to be named "var" or
                // "signal" (${var:-default}, ${var:=x}, ${var:+x}, ${var:?msg}) collides with
                // our prefix. Those modifier chars can't begin a variable/signal name, so
                // treat them as the shell's and pass them through rather than refusing.
                if (nameAt >= 0 && nameAt < input.length()) {
                    char c = input.charAt(nameAt);
                    if (c == '-' || c == '=' || c == '+' || c == '?') nameAt = -1;
                }
                if (nameAt < 0) {
                    out.append(input.charAt(i++));
                    continue;
                }
                int close = input.indexOf('}', i + 2);
                if (close < 0) return null;           // unterminated OUR token → refuse
                // Resolve through the shared resolver so signal addressing is identical
                // to a condition's. The full "${…}" text is what it expects.
                String resolved = com.overdrive.app.automation.AutomationCondition
                        .resolveDynamicToString(input.substring(i, close + 1));
                if (resolved == null) return null;
                Double value;
                try {
                    value = Double.valueOf(resolved.trim());
                } catch (NumberFormatException e) {
                    return null;                      // non-numeric state → refuse
                }
                String text = numericText(value);
                if (text == null) return null;
                out.append(text);
                i = close + 1;
            }
            return out.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The digits-only text for a resolved value, or null if it isn't a finite number.
     * This is the injection boundary: {@link ExpressionEvaluator#format} only ever emits
     * digits, {@code -} and {@code .}, but the check is asserted here rather than trusted,
     * so no state value can introduce a quote, semicolon, backtick, {@code $} or space
     * into a command line running as UID 2000.
     */
    private static String numericText(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return null;
        String text = ExpressionEvaluator.format(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '-' && c != '.') return null;
        }
        return text;
    }
}

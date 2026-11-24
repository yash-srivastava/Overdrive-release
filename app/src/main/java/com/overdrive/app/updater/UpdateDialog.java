package com.overdrive.app.updater;

import android.animation.ObjectAnimator;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.overdrive.app.R;

public class UpdateDialog {

    public static void showUpdateAvailable(Context context, String currentVersion,
                                           String newVersion, String releaseNotes,
                                           Runnable onUpdate, Runnable onDismiss) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_update_available, null);
        // currentVersion (getDisplayVersion) and newVersion (extractVersion) are
        // already self-prefixed labels like "alpha-v26.1" / "v26.1" — do NOT add
        // another "v" or it reads "valpha-v26.1".
        ((TextView) view.findViewById(R.id.updateCurrentVersion)).setText(currentVersion);
        ((TextView) view.findViewById(R.id.updateNewVersion)).setText(newVersion);

        TextView notes = view.findViewById(R.id.updateReleaseNotes);
        CharSequence rendered = markdownToSpannable(releaseNotes);
        if (rendered == null || rendered.length() == 0) {
            // No changelog → hide the "What's new" label + notes area entirely
            // rather than show an empty scroll region.
            view.findViewById(R.id.updateNotesLabel).setVisibility(View.GONE);
            notes.setVisibility(View.GONE);
        } else {
            notes.setText(rendered);
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                context, R.style.Theme_Overdrive_M3_Dialog)
                .setView(view)
                .setPositiveButton(R.string.update_dialog_install_now, (d, w) -> { d.dismiss(); onUpdate.run(); })
                .setNegativeButton(R.string.update_dialog_later, (d, w) -> { d.dismiss(); if (onDismiss != null) onDismiss.run(); })
                // Route back-press / outside-tap dismissal through onDismiss
                // too. setCancelable(true) without this listener silently
                // skipped the dismiss callback, leaking the AppUpdater
                // instance (its lazy-allocated AdbDaemonLauncher's executor
                // + tunnel-poll scheduler) until process death.
                .setOnCancelListener(d -> { if (onDismiss != null) onDismiss.run(); })
                .setCancelable(true)
                .show();
    }

    /** Callback for the alpha version picker \u2014 receives the chosen entry. */
    public interface VersionPickListener {
        void onPick(com.overdrive.app.updater.AppUpdater.VersionEntry entry);
        void onDismiss();
    }

    /**
     * Single-choice picker over the alpha archive (pick-any). Each row shows
     * the version label plus an "(installed)" suffix on the current build.
     * The installed version is pre-selected. Confirming fires onPick with the
     * chosen entry; the caller then runs prepareInstall + the normal install
     * flow. A downgrade is allowed (pm install -d) \u2014 the chosen entry's
     * relation lets the caller surface a downgrade note if desired.
     */
    public static void showVersionPicker(Context context,
                                         java.util.List<com.overdrive.app.updater.AppUpdater.VersionEntry> versions,
                                         VersionPickListener listener) {
        if (versions == null || versions.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    context, R.style.Theme_Overdrive_M3_Dialog)
                    .setTitle(R.string.update_picker_title)
                    .setMessage(R.string.update_picker_empty)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        d.dismiss();
                        if (listener != null) listener.onDismiss();
                    })
                    .setOnCancelListener(d -> { if (listener != null) listener.onDismiss(); })
                    .show();
            return;
        }

        CharSequence[] labels = new CharSequence[versions.size()];
        int preselect = 0;
        for (int i = 0; i < versions.size(); i++) {
            com.overdrive.app.updater.AppUpdater.VersionEntry v = versions.get(i);
            String label = v.version != null ? v.version : v.tag;
            if ("current".equals(v.relation)) {
                label = context.getString(R.string.update_picker_current_suffix, label);
                preselect = i;
            } else if ("older".equals(v.relation)) {
                label = context.getString(R.string.update_picker_older_suffix, label);
            }
            labels[i] = label;
        }

        final int[] chosen = { preselect };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                context, R.style.Theme_Overdrive_M3_Dialog)
                .setTitle(R.string.update_picker_title)
                .setIcon(R.drawable.ic_update)
                .setSingleChoiceItems(labels, preselect, (d, which) -> chosen[0] = which)
                .setPositiveButton(R.string.update_dialog_install_now, (d, w) -> {
                    d.dismiss();
                    if (listener != null) listener.onPick(versions.get(chosen[0]));
                })
                .setNegativeButton(R.string.update_dialog_later, (d, w) -> {
                    d.dismiss();
                    if (listener != null) listener.onDismiss();
                })
                .setOnCancelListener(d -> { if (listener != null) listener.onDismiss(); })
                .setCancelable(true)
                .show();
    }

    public static ProgressHandle showProgress(Context context, Runnable onCancel) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_update_progress, null);
        TextView statusText = view.findViewById(R.id.updateStatusText);
        ImageView statusIcon = view.findViewById(R.id.updateStatusIcon);
        com.google.android.material.progressindicator.LinearProgressIndicator progressBar =
                view.findViewById(R.id.updateProgressBar);
        TextView percentText = view.findViewById(R.id.updatePercentText);

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                context, R.style.Theme_Overdrive_M3_Dialog)
                .setTitle(R.string.update_progress_title)
                .setIcon(R.drawable.ic_update)
                .setView(view)
                // "Hide" not "Cancel": the install runs in the daemon and can't
                // be aborted from here (mirrors the webapp). The callback just
                // stops the app-side poll loop; the install continues.
                .setNegativeButton(R.string.update_hide, (d, w) -> { if (onCancel != null) onCancel.run(); d.dismiss(); })
                .setCancelable(false)
                .show();
        // Some vendor dialog themes still propagate an outside touch to the
        // window even when the builder is non-cancelable. Make the window-level
        // contract explicit: progress may be hidden only with the Hide button.
        dialog.setCanceledOnTouchOutside(false);

        return new ProgressHandle(context, dialog, statusText, statusIcon, progressBar, percentText);
    }

    static SpannableStringBuilder markdownToSpannable(String markdown) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (markdown == null || markdown.isEmpty()) return sb;
        for (String line : markdown.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append("\n"); continue; }
            if (t.startsWith("###")) {
                String text = t.replaceFirst("^#{1,3}\\s*", "");
                int s = sb.length(); sb.append(text);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.1f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n"); continue;
            }
            if (t.startsWith("##")) {
                String text = t.replaceFirst("^#{1,2}\\s*", "");
                int s = sb.length(); sb.append(text);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.2f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n"); continue;
            }
            if (t.startsWith("* ") || t.startsWith("- ")) {
                String text = t.substring(2).replaceAll("\\*\\*(.+?)\\*\\*", "$1");
                int s = sb.length(); sb.append(text);
                sb.setSpan(new BulletSpan(16), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n"); continue;
            }
            if (t.matches("^-{3,}$")) { sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n"); continue; }
            sb.append(t.replaceAll("\\*\\*(.+?)\\*\\*", "$1")).append("\n");
        }
        return sb;
    }

    public static class ProgressHandle {
        private final Context context;
        private final AlertDialog dialog;
        private final TextView statusText;
        private final ImageView statusIcon;
        private final com.google.android.material.progressindicator.LinearProgressIndicator progressBar;
        private final TextView percentText;

        ProgressHandle(Context context, AlertDialog dialog, TextView statusText, ImageView statusIcon,
                       com.google.android.material.progressindicator.LinearProgressIndicator progressBar,
                       TextView percentText) {
            this.context = context;
            this.dialog = dialog;
            this.statusText = statusText;
            this.statusIcon = statusIcon;
            this.progressBar = progressBar;
            this.percentText = percentText;
        }

        /**
         * Set a step by string-res + state icon, animating the bar to the
         * target percentage. The icon (not a text emoji) carries the visual
         * cue, matching the no-emoji-in-UI house rule.
         */
        public void setStep(int statusRes, int iconRes, int targetPercent) {
            statusText.setText(context.getString(statusRes));
            setIcon(iconRes, false);
            percentText.setText(targetPercent + "%");
            animateTo(targetPercent);
        }

        /** Animate progress bar to target percentage with raw status text. */
        public void setStep(String status, int targetPercent) {
            statusText.setText(status);
            percentText.setText(targetPercent + "%");
            animateTo(targetPercent);
        }

        private void animateTo(int targetPercent) {
            // Leave indeterminate mode (if we were in it) before showing a
            // concrete value — a determinate animation on an indeterminate
            // LinearProgressIndicator is ignored.
            if (progressBar.isIndeterminate()) {
                progressBar.setIndeterminate(false);
            }
            ObjectAnimator anim = ObjectAnimator.ofInt(progressBar, "progress", progressBar.getProgress(), targetPercent);
            anim.setDuration(600);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.start();
        }

        private void setIcon(int iconRes, boolean error) {
            statusIcon.setImageResource(iconRes);
            int tintAttr = error
                    ? androidx.appcompat.R.attr.colorError
                    : androidx.appcompat.R.attr.colorPrimary;
            android.util.TypedValue tv = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(tintAttr, tv, true)) {
                statusIcon.setColorFilter(tv.data);
            }
        }

        public void setStatus(String status) {
            statusText.setText(status);
        }

        public void setProgress(int percent) {
            progressBar.setProgress(percent);
            percentText.setText(percent + "%");
        }

        public void setIndeterminate(String status) {
            statusText.setText(status);
            setIcon(R.drawable.ic_arrow_down, false);
            // Switch the bar to the M3 indeterminate sweep so a download with no
            // Content-Length (CDN/proxy strips it) reads as "working" instead of
            // a frozen percentage. percentText is cleared — no number to show.
            if (!progressBar.isIndeterminate()) {
                progressBar.setIndeterminate(true);
            }
            percentText.setText("");
        }

        public void dismiss() {
            dialog.dismiss();
        }

        public boolean isShowing() {
            return dialog.isShowing();
        }

        public void showError(String error) {
            statusText.setText(error);
            setIcon(R.drawable.ic_error, true);
            percentText.setText("");
            progressBar.setProgress(0);
            dialog.setCancelable(true);
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, context.getString(android.R.string.ok), (d, w) -> d.dismiss());
        }
    }
}

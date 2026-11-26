package com.overdrive.app.automation.type;

import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;

/**
 * An uploaded-sound selector. The value is a sound file NAME (as stored in the audio
 * library, e.g. "alert.mp3"). The frontend renders it as a dropdown populated live
 * from {@code GET /api/audio/library}, so — unlike {@link EnumType} — the option set
 * is NOT baked into the schema (the user's uploaded sounds differ per device and
 * change over time). Mirrors {@link AppType}, which does the same for installed apps.
 *
 * <p>Reuses {@link StringValue} as its backing value so no new Value type or
 * {@code Automations.update} overload is needed.
 */
public class AudioType extends StringSchemaType {
    private static final String TYPE = "audio";

    public AudioType(Label label) {
        super(label, TYPE);
    }

    /**
     * A sound name is valid if it is a non-empty, bounded string in the audio-library
     * filename charset ([A-Za-z0-9 ._-]) ending in a supported audio extension. This
     * mirrors the daemon's safeAudioName() so a hand-edited automation can't smuggle a
     * path-traversal or a quote that breaks the {@code {"name":"<value>"}} JSON body.
     */
    public boolean isValidValue(String value) {
        if (!isBoundedToken(value, 80, "._- ")) return false;
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot >= value.length() - 1) return false;
        String ext = value.substring(dot + 1).toLowerCase();
        boolean okExt = ext.equals("mp3") || ext.equals("wav") || ext.equals("mp4")
                || ext.equals("m4a") || ext.equals("aac") || ext.equals("ogg");
        return okExt;
    }
}

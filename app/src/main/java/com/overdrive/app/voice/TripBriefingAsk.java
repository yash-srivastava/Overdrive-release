package com.overdrive.app.voice;

import java.util.Collections;
import java.util.List;

/** A Trip Briefing question for the voice assistant (dev/trip-briefing/00-DESIGN.md §7). */
public final class TripBriefingAsk {

    public static final String ANSWER_YES = "yes";
    public static final String ANSWER_NO = "no";
    /** Put off, not refused ("maybe later", another place first): asked again another day, no step toward a pause. */
    public static final String ANSWER_LATER = "later";
    public static final String ANSWER_NONE = "none";

    public static final String APP_OVERDRIVE = "overdrive";
    public static final String APP_WAZE = "waze";
    public static final String APP_GMAPS = "gmaps";
    public static final String APP_CAR = "car";

    public static final String VIA_VOICE = "voice";
    public static final String VIA_BUSY = "busy";

    /** Offer id from the daemon. */
    public final String id;
    /** The full question in the app language, spoken in Local and Hybrid modes. */
    public final String spokenLine;
    /** Destination as said ("work", "Phoenix Mall"), given to the AI in GenAI mode. */
    public final String destLabel;
    /** Typical drive in minutes, or 0 when unknown; AI context only. */
    public final int durationMin;
    /** The extra line already phrased (traffic, range, hazards, rain), or null; AI context only. */
    public final String extraLine;
    /** Installed navigation apps, default first. */
    public final List<String> apps;
    public final long timeoutMs;
    /** A recorded clip of the question (VoicePrompts key) for a head unit with no speech engine, or null. */
    public final String promptClip;
    /**
     * A yes/no follow-up on the trip just started (the cluster), in the briefing's voice: the AI asks where it asked
     * the briefing, else the car. No directions context goes with it.
     */
    public final boolean followUp;

    public TripBriefingAsk(String id, String spokenLine, String destLabel, int durationMin, String extraLine,
                           List<String> apps, long timeoutMs) {
        this(id, spokenLine, destLabel, durationMin, extraLine, apps, timeoutMs, null, false);
    }

    public TripBriefingAsk(String id, String spokenLine, String destLabel, int durationMin, String extraLine,
                           List<String> apps, long timeoutMs, String promptClip, boolean followUp) {
        this.id = id;
        this.spokenLine = spokenLine;
        this.destLabel = destLabel;
        this.durationMin = durationMin;
        this.extraLine = extraLine;
        this.apps = apps == null ? Collections.<String>emptyList() : Collections.unmodifiableList(apps);
        this.timeoutMs = timeoutMs;
        this.promptClip = promptClip;
        this.followUp = followUp;
    }

    /** Called once, on the main thread. [app] is null unless the driver named one. */
    public interface Callback {
        void onAnswer(String answer, String app, String via);
    }

    /** Ends the turn early (the card was answered); the callback doesn't fire after this. No-op once answered. */
    public interface Handle {
        void cancel();
    }
}

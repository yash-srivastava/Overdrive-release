package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Pure sidecar-label decisions; no detector, Android device, or video required. */
public class EventTimelineAnimalLabelTest {
    private static final long CLIP_START = 100_000L;
    private static final int FRONT = 1;
    private static final int RIGHT = 2;

    private static Actor actor(Actor.ClassGroup group, boolean confirmed,
                               boolean timelineStatic, float confidence,
                               long firstWallMs, long lastWallMs, int cameraMask) {
        return new Actor(1L, group, firstWallMs, lastWallMs,
                firstWallMs - CLIP_START, lastWallMs - CLIP_START,
                cameraMask, Actor.Proximity.MID, Actor.Proximity.MID,
                Actor.Trend.STABLE, false, timelineStatic,
                true, true, confirmed, Actor.Severity.NOTICE,
                firstWallMs, firstWallMs - CLIP_START, confidence,
                10, 20, 50, 45, 320, 240, 0,
                10, 20, 50, 45, 0);
    }

    private static boolean relabel(int cameras, int maxCount, float personConf,
                                    Actor... actors) {
        return EventTimelineCollector.shouldLabelAsAnimal(
                1000L, 6000L, cameras, maxCount, personConf,
                CLIP_START, Arrays.asList(actors));
    }

    @Test public void confirmedMovingAnimalOutweighsOneFramePerson() {
        assertTrue(relabel(FRONT, 1, 0.54f,
                actor(Actor.ClassGroup.ANIMAL, true, false, 0.81f,
                        101_500L, 104_000L, FRONT),
                actor(Actor.ClassGroup.PERSON, false, false, 0.54f,
                        102_000L, 102_000L, FRONT)));
    }

    @Test public void confirmedPersonAlwaysWinsEvenBesideAnimal() {
        assertFalse(relabel(FRONT, 1, 0.54f,
                actor(Actor.ClassGroup.ANIMAL, true, false, 0.95f,
                        101_500L, 104_000L, FRONT),
                actor(Actor.ClassGroup.PERSON, true, false, 0.54f,
                        102_000L, 102_500L, FRONT)));
    }

    @Test public void noPositiveAnimalEvidenceKeepsPerson() {
        assertFalse(EventTimelineCollector.shouldLabelAsAnimal(
                1000L, 6000L, FRONT, 1, 0.4f, CLIP_START,
                Collections.<Actor>emptyList()));
        assertFalse(relabel(FRONT, 1, 0.4f,
                actor(Actor.ClassGroup.ANIMAL, false, false, 0.95f,
                        101_500L, 104_000L, FRONT)));
        assertFalse(relabel(FRONT, 1, 0.4f,
                actor(Actor.ClassGroup.ANIMAL, true, true, 0.95f,
                        101_500L, 104_000L, FRONT)));
    }

    @Test public void strongerPersonOrDifferentWindowKeepsPerson() {
        assertFalse(relabel(FRONT, 1, 0.92f,
                actor(Actor.ClassGroup.ANIMAL, true, false, 0.81f,
                        101_500L, 104_000L, FRONT)));
        assertFalse(relabel(FRONT, 1, 0.54f,
                actor(Actor.ClassGroup.ANIMAL, true, false, 0.81f,
                        110_000L, 111_000L, FRONT)));
        assertFalse(relabel(FRONT, 1, 0.54f,
                actor(Actor.ClassGroup.ANIMAL, true, false, 0.81f,
                        101_500L, 104_000L, RIGHT)));
    }

    @Test public void sharedCameraOrTwoObjectsFailOpen() {
        Actor dog = actor(Actor.ClassGroup.ANIMAL, true, false, 0.81f,
                101_500L, 104_000L, FRONT);
        assertFalse(relabel(FRONT | RIGHT, 1, 0.54f, dog));
        assertFalse(relabel(FRONT, 2, 0.54f, dog));
    }
}

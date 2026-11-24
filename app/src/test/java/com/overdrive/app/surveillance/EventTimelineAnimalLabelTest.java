package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.overdrive.app.ai.Detection;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Collector-only detection evidence: no actor retention, Android device or recording needed. */
public class EventTimelineAnimalLabelTest {
    private static Detection dog(float confidence) {
        return new Detection(16, confidence, 10, 20, 60, 50);
    }

    private static Detection person(float confidence) {
        return new Detection(0, confidence, 12, 21, 57, 48);
    }

    private static Detection passingPerson() {
        return new Detection(0, 0.56f, 180, 30, 35, 70);
    }

    private static EventTimelineCollector.AnimalFrameEvidence dogWithFlicker() {
        EventTimelineCollector.AnimalFrameEvidence e =
                new EventTimelineCollector.AnimalFrameEvidence();
        e.observe(Collections.singletonList(dog(0.84f)), 1000);
        e.observe(Collections.singletonList(dog(0.81f)), 1500);
        e.observe(Collections.singletonList(person(0.54f)), 2000);
        e.observe(Collections.singletonList(dog(0.76f)), 2500);
        e.observe(Collections.singletonList(dog(0.79f)), 3000);
        return e;
    }

    @Test public void isolatedPersonFlickerIsAnAnimalWithAnimalPeakConfidence() {
        EventTimelineCollector.AnimalFrameEvidence e = dogWithFlicker();
        assertEquals(1, e.personFrames);
        assertEquals(4, e.animalFrames);
        assertEquals(0.84f, e.peakAnimalConfidence, 0.001f);
        assertTrue(e.shouldRelabel(1, 1, 0.54f));
        assertFalse(e.shouldRelabel(1, 1, 0.94f)); // person confidence wins
    }

    @Test public void dogThenRealPersonNeverErasesTrailingPerson() {
        EventTimelineCollector.AnimalFrameEvidence e = dogWithFlicker();
        e.observe(Collections.singletonList(passingPerson()), 3500);
        assertFalse(e.shouldRelabel(1, 1, 0.54f));
    }

    @Test public void personAtStartOfPreRollStaysPerson() {
        EventTimelineCollector.AnimalFrameEvidence e =
                new EventTimelineCollector.AnimalFrameEvidence();
        e.observe(Collections.singletonList(person(0.54f)), 1000);
        for (int i = 0; i < 6; i++) {
            e.observe(Collections.singletonList(dog(0.9f)), 1500 + i * 500);
        }
        assertFalse(e.shouldRelabel(1, 1, 0.54f));
    }

    @Test public void simultaneousPersonAndDogAlwaysRetainsPerson() {
        EventTimelineCollector.AnimalFrameEvidence e = dogWithFlicker();
        e.observe(Arrays.asList(person(0.54f), dog(0.91f)), 3500);
        assertFalse(e.shouldRelabel(1, 1, 0.54f));
    }

    @Test public void differentPersonBoxNeverDisappears() {
        EventTimelineCollector.AnimalFrameEvidence e =
                new EventTimelineCollector.AnimalFrameEvidence();
        e.observe(Collections.singletonList(dog(0.80f)), 1000);
        e.observe(Collections.singletonList(dog(0.80f)), 1500);
        e.observe(Collections.singletonList(passingPerson()), 2000);
        e.observe(Collections.singletonList(dog(0.80f)), 2500);
        e.observe(Collections.singletonList(dog(0.80f)), 3000);
        assertFalse(e.shouldRelabel(1, 1, 0.56f));
    }

    @Test public void delayedNewPersonCannotBeMatchedToExpiredDog() {
        EventTimelineCollector.AnimalFrameEvidence e =
                new EventTimelineCollector.AnimalFrameEvidence();
        e.observe(Collections.singletonList(dog(0.8f)), 1000);
        e.observe(Collections.singletonList(dog(0.8f)), 1500);
        e.observe(Collections.singletonList(person(0.5f)), 3100);
        e.observe(Collections.singletonList(dog(0.8f)), 3500);
        e.observe(Collections.singletonList(dog(0.8f)), 4000);
        assertFalse(e.shouldRelabel(1, 1, 0.5f));
    }

    @Test public void twoPersonFramesNeedBracketingAnimalEvidence() {
        EventTimelineCollector.AnimalFrameEvidence e =
                new EventTimelineCollector.AnimalFrameEvidence();
        e.observe(Collections.singletonList(dog(0.9f)), 1000);
        e.observe(Collections.singletonList(dog(0.8f)), 1500);
        e.observe(Collections.singletonList(person(0.55f)), 2000);
        e.observe(Collections.singletonList(person(0.55f)), 2300);
        e.observe(Collections.singletonList(dog(0.86f)), 2600);
        e.observe(Collections.singletonList(dog(0.85f)), 3000);
        e.observe(Collections.singletonList(dog(0.83f)), 3500);
        e.observe(Collections.singletonList(dog(0.82f)), 3900);
        assertTrue(e.shouldRelabel(1, 1, 0.55f));
        e.observe(Collections.singletonList(person(0.55f)), 4200);
        assertFalse(e.shouldRelabel(1, 1, 0.55f));
    }

    @Test public void multiCameraAndMultiObjectStayPerson() {
        EventTimelineCollector.AnimalFrameEvidence e = dogWithFlicker();
        assertFalse(e.shouldRelabel(3, 1, 0.54f));
        assertFalse(e.shouldRelabel(1, 2, 0.54f));
        assertFalse(e.shouldRelabel(0, 1, 0.54f));
    }

    @Test public void noActorSnapshotIsRequiredAtEndOfPostRoll() {
        // The decision deliberately takes no Actor list. It survives when the
        // tracker expires 8 s into a 10 s post-roll and retains no animals.
        EventTimelineCollector.AnimalFrameEvidence e = dogWithFlicker();
        assertTrue(e.shouldRelabel(1, 1, 0.54f));
    }
}

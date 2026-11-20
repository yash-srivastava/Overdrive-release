package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Exercises firmware selection rather than testing source-text predicates. */
public class Di3AccHandoffPolicyTest {
    private static final String DI3 =
            "BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1/eng:user/release-keys";
    private static final String DI4 =
            "BYD-AUTO/DiLink4.0/DiLink4.0:11/RKQ1/eng:user/release-keys";

    @Test
    public void di3BuildEnablesTheBoundedRetry() {
        assertTrue(Di3AccHandoffPolicy.eligible(
                true, false, false, "DiLink3.0", "DiLink3.0", DI3));
        assertTrue(Di3AccHandoffPolicy.eligible(
                true, false, false, "DiLink3.0", "", ""));
    }

    @Test
    public void dilink4InDefaultModeMustNotTakeTheDi3Path() {
        assertFalse(Di3AccHandoffPolicy.eligible(
                true, false, false, "DiLink4.0", "DiLink4.0", DI4));
        assertFalse(Di3AccHandoffPolicy.eligible(
                true, false, false, "DiLink3.0", "DiLink3.0", DI4));
        assertFalse(Di3AccHandoffPolicy.eligible(
                true, false, true, "DiLink3.0", "DiLink3.0", DI3));
    }

    @Test
    public void byd100fAndOtherCameraPathsRemainUnchanged() {
        assertFalse(Di3AccHandoffPolicy.eligible(
                true, true, false, "DiLink3.0", "DiLink3.0", DI3));
        assertFalse(Di3AccHandoffPolicy.eligible(
                false, false, false, "DiLink3.0", "DiLink3.0", DI3));
        assertFalse(Di3AccHandoffPolicy.eligible(
                true, false, false, "BYD AUTO", "", ""));
        assertFalse(Di3AccHandoffPolicy.eligible(
                true, false, false, "DiLink30", "", ""));
    }
}

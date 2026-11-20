package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Exercises scope without maintaining a second firmware parser. */
public class Di3AccHandoffPolicyTest {
    @Test
    public void onlyDi3LegacyOpenIsEligible() {
        assertTrue(Di3AccHandoffPolicy.eligible(true, false, false, true));
        assertFalse(Di3AccHandoffPolicy.eligible(true, false, false, false));
        assertFalse(Di3AccHandoffPolicy.eligible(false, false, false, true));
    }

    @Test
    public void byd100fAndSelectedDiLink4AreUnchanged() {
        assertFalse(Di3AccHandoffPolicy.eligible(true, true, false, true));
        assertFalse(Di3AccHandoffPolicy.eligible(true, false, true, true));
    }
}

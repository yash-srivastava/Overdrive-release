package com.overdrive.app.abrp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins {@link SohEstimator#mapCarTypeToCapacity(String)}'s branch ORDERING.
 *
 * <p>The function is a waterfall of {@code contains()} checks, so a PHEV (DM-i / DM-p) model
 * must be matched BEFORE its BEV namesake or it silently inherits the BEV pack size. That is
 * how a Tang DM-i was being detected as a 108.8 kWh BEV pack — a ~5x nominal overstatement
 * that propagates into SOH, remaining-kWh, trip energy and range, since every one of those is
 * derived from nominal capacity.
 *
 * <p>These tests exist because the failure is invisible: the wrong value is perfectly
 * plausible-looking, and no caller can assert on ordering. They also guard the reverse
 * regression — adding a DM-i branch must not steal a BEV string.
 */
public class SohModelCapacityTest {

    /** DM-i / DM-p variants must resolve to their (much smaller) PHEV packs. */
    @Test
    public void dmiVariantsResolveToPhevPacks() {
        // Tang DM-i is the headline bug: it used to fall through to the 108.8 kWh BEV branch.
        assertEquals(21.5, SohEstimator.mapCarTypeToCapacity("Tang DM-i"), 0.001);
        assertEquals(21.5, SohEstimator.mapCarTypeToCapacity("TANG DMI"), 0.001);
        assertEquals(21.5, SohEstimator.mapCarTypeToCapacity("Tang DM-p"), 0.001);

        assertEquals(18.3, SohEstimator.mapCarTypeToCapacity("Song Plus DM-i"), 0.001);
        assertEquals(18.3, SohEstimator.mapCarTypeToCapacity("Song Pro DMI"), 0.001);
        assertEquals(18.3, SohEstimator.mapCarTypeToCapacity("Qin Plus DM-i"), 0.001);
        assertEquals(18.3, SohEstimator.mapCarTypeToCapacity("Frigate 07 DM-i"), 0.001);
        assertEquals(18.3, SohEstimator.mapCarTypeToCapacity("Seal U DM-i"), 0.001);
        assertEquals(18.3, SohEstimator.mapCarTypeToCapacity("Destroyer 05"), 0.001);
    }

    /**
     * Every BEV must be UNAFFECTED by the DM-i branches. This is the regression half: a new
     * DM-i check placed too early, or one whose keyword is too loose, would silently shrink a
     * BEV's pack.
     */
    @Test
    public void bevModelsAreUnaffected() {
        assertEquals(108.8, SohEstimator.mapCarTypeToCapacity("Tang"), 0.001);
        assertEquals(108.8, SohEstimator.mapCarTypeToCapacity("Tang EV"), 0.001);
        assertEquals(71.8, SohEstimator.mapCarTypeToCapacity("Song"), 0.001);
        assertEquals(71.8, SohEstimator.mapCarTypeToCapacity("Song L EV"), 0.001);
        assertEquals(56.4, SohEstimator.mapCarTypeToCapacity("Qin"), 0.001);
        assertEquals(56.4, SohEstimator.mapCarTypeToCapacity("Qin L EV"), 0.001);
        assertEquals(82.56, SohEstimator.mapCarTypeToCapacity("Seal"), 0.001);
        assertEquals(71.8, SohEstimator.mapCarTypeToCapacity("Seal U"), 0.001);
        assertEquals(26.6, SohEstimator.mapCarTypeToCapacity("Sealion 6"), 0.001);
        assertEquals(29.6, SohEstimator.mapCarTypeToCapacity("Shark 6"), 0.001);
        assertEquals(29.6, SohEstimator.mapCarTypeToCapacity("BYD SHARK 6"), 0.001);
        assertEquals(91.3, SohEstimator.mapCarTypeToCapacity("Sealion 7"), 0.001);
        assertEquals(60.48, SohEstimator.mapCarTypeToCapacity("Atto 3"), 0.001);
        assertEquals(44.9, SohEstimator.mapCarTypeToCapacity("Dolphin"), 0.001);
        assertEquals(38.0, SohEstimator.mapCarTypeToCapacity("Seagull"), 0.001);
    }

    /** Han BEV keeps its 85.44 kWh pack. */
    @Test
    public void hanBevKeepsItsCapacity() {
        assertEquals(85.44, SohEstimator.mapCarTypeToCapacity("Han"), 0.001);
        assertEquals(85.44, SohEstimator.mapCarTypeToCapacity("Han EV"), 0.001);
        assertEquals(85.44, SohEstimator.mapCarTypeToCapacity("HAN"), 0.001);
    }

    /**
     * A Han PHEV must NOT inherit the 85.44 kWh BEV pack — that is a ~4.7x overstatement of a
     * PHEV pack, the same class of bug as Tang DM-i.
     *
     * <p>It resolves to 0 = "not detected" rather than to a number, and that is the deliberate
     * choice being pinned here: the Han PHEV pack varies too widely across trims/model years for
     * a single constant to be honest, and a confidently-wrong one is indistinguishable from a
     * real detection downstream. 0 makes the caller fall through to the measurement-based tiers.
     *
     * <p>(An earlier version of this test asserted the opposite — that every Han form keeps
     * 85.44 — on the rationale that the Han DM-p carries an ~85 kWh-class pack. It does not;
     * Han PHEV packs are small, so that assertion was pinning the bug in place.)
     */
    @Test
    public void hanPhevDoesNotInheritBevPack() {
        for (String s : new String[]{"Han DM-i", "Han DM-p", "HAN DMI", "han dm-i"}) {
            assertEquals("Han PHEV '" + s + "' must not resolve to the BEV pack",
                    0.0, SohEstimator.mapCarTypeToCapacity(s), 0.001);
        }
    }

    /**
     * "DM-p" on its own must not be treated as a Han. The bare {@code || ct.contains("DM-P")}
     * that used to sit on the HAN branch handed an 85.44 kWh BEV pack to ANY DM-p model that
     * reached it — a PHEV inheriting a BEV capacity, i.e. this file's whole subject.
     */
    @Test
    public void bareDmpStringIsNotAHan() {
        assertEquals(0.0, SohEstimator.mapCarTypeToCapacity("DM-p"), 0.001);
        assertEquals(0.0, SohEstimator.mapCarTypeToCapacity("BYD DM-p"), 0.001);
        // ...but a NAMED DM-p still resolves through its own model branch.
        assertEquals(21.5, SohEstimator.mapCarTypeToCapacity("Tang DM-p"), 0.001);
    }

    /** An unrecognised string must resolve to 0 (caller treats that as "no detection"). */
    @Test
    public void unknownModelResolvesToZero() {
        assertEquals(0.0, SohEstimator.mapCarTypeToCapacity("Unknown Model"), 0.001);
        assertEquals(0.0, SohEstimator.mapCarTypeToCapacity(""), 0.001);
    }

    /** Matching must be case-insensitive — the HAL reports mixed case across trims. */
    @Test
    public void matchingIsCaseInsensitive() {
        assertEquals(21.5, SohEstimator.mapCarTypeToCapacity("tang dm-i"), 0.001);
        assertEquals(108.8, SohEstimator.mapCarTypeToCapacity("TANG"), 0.001);
    }
}

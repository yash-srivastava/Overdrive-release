package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.action.Actions;

import org.junit.Test;

/** IF/WHEN and THEN use the same feature ids, but IF reads real vehicle state. */
public class AdasStateEventContractTest {
    @Test
    public void espAndItacAreAvailableOnBothSidesOfARule() {
        Conditions conditions = new Conditions();
        Actions actions = new Actions();
        for (EventData event : new EventData[]{BydEvent.ESP_CONTROL, BydEvent.ITAC}) {
            String id = event.getType();
            EventCondition condition = conditions.getCondition(id);
            assertNotNull("Missing condition: " + id, condition);
            assertNotNull("Missing action: " + id, actions.getAction(id));
            assertEquals(event, condition.eventData(event.toJson()));
            assertEquals(AutomationCategories.ADAS_SAFETY,
                    AutomationCategories.forId(id));
        }
    }

    @Test
    public void espUsesInvertedReadbackAndNeverGuessesUnknownValues() {
        assertEquals("on", AdasStateEvent.espWord(0));
        assertEquals("off", AdasStateEvent.espWord(1));
        assertNull(AdasStateEvent.espWord(-1));
        assertNull(AdasStateEvent.espWord(2));
        assertNull(AdasStateEvent.espWord(255));
    }

    @Test
    public void gettersAreDisabledWhenParkedOrOnDiLink5() {
        assertTrue(AdasStateEvent.canRead(false, true));
        assertFalse(AdasStateEvent.canRead(false, false));
        assertFalse(AdasStateEvent.canRead(true, true));
        assertFalse(AdasStateEvent.canRead(true, false));
    }

    @Test
    public void itacPolarityIsProvisionalUntilOnCarVerification() {
        // Provisional interpretation from other tools; needs a real DiLink 3/4 car.
        assertEquals("on", AdasStateEvent.itacWord(0));
        assertEquals("off", AdasStateEvent.itacWord(1));
        assertEquals("off", AdasStateEvent.itacWord(2));
        assertNull(AdasStateEvent.itacWord(-1));
        assertNull(AdasStateEvent.itacWord(255));
    }
}

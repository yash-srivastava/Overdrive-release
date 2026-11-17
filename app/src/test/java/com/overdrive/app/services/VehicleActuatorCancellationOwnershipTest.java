package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.LocalActuationCancellation;
import com.overdrive.app.byd.VehicleActuatorBridge;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class VehicleActuatorCancellationOwnershipTest {

    @Test
    public void serviceNegotiatesCancellationOnlyForManagedOptedInRequests() {
        assertNull(VehicleActuatorService.createDiLink5Cancellation(false, false));
        assertNull(VehicleActuatorService.createDiLink5Cancellation(true, false));
        assertNull(VehicleActuatorService.createDiLink5Cancellation(false, true));
        assertNotNull(VehicleActuatorService.createDiLink5Cancellation(true, true));
    }

    @Test
    public void bridgeLinksCancellationBeforeAndAfterServiceRegistration() throws Exception {
        LocalActuationCancellation cancelledBeforeReply = new LocalActuationCancellation();
        LocalActuationCancellation firstServiceOwner =
                VehicleActuatorService.createDiLink5Cancellation(true, true);
        cancelledBeforeReply.cancel();
        assertTrue(VehicleActuatorBridge.registerDiLink5CancellationOwner(
                cancelledBeforeReply, firstServiceOwner::cancel));
        assertTrue(firstServiceOwner.isCancelled());

        LocalActuationCancellation cancelledAfterReply = new LocalActuationCancellation();
        LocalActuationCancellation secondServiceOwner =
                VehicleActuatorService.createDiLink5Cancellation(true, true);
        assertTrue(VehicleActuatorBridge.registerDiLink5CancellationOwner(
                cancelledAfterReply, secondServiceOwner::cancel));
        assertFalse(secondServiceOwner.isCancelled());
        cancelledAfterReply.cancel();
        assertTrue(secondServiceOwner.isCancelled());

        AtomicInteger setters = new AtomicInteger();
        boolean dispatched = VehicleActuatorBridge.withRemoteKeyRequestCancellation(
                secondServiceOwner,
                () -> VehicleActuatorBridge.dispatchWhileRemoteKeyRequestActive(
                        () -> {
                            setters.incrementAndGet();
                            return true;
                        },
                        false));
        assertFalse(dispatched);
        assertEquals(0, setters.get());
    }

    @Test
    public void malformedOwnershipReplyFailsClosed() {
        LocalActuationCancellation caller = new LocalActuationCancellation();
        assertFalse(VehicleActuatorBridge.registerDiLink5CancellationOwner(caller, null));
        assertFalse(VehicleActuatorBridge.registerDiLink5CancellationOwner(null, caller::cancel));
        assertFalse(caller.isCancelled());
    }
}

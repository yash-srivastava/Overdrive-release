package com.overdrive.app.remoteaccess;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Executes the same callback dispatch/state used by the reader without Android services. */
public class BluetoothRssiCallbacksTest {
    private static final String ADDRESS = "AA:BB:CC:DD:EE:FF";

    public interface LocalCallback {
        Object asBinder();
        void onClientRegistered(int status, int client);
        void onReadRemoteRssi(String address, int rssi, int status);
        void unrelated(String value);
    }

    public interface WrongCallback {
        void onClientRegistered(long status, int client);
        void onReadRemoteRssi(String address, long rssi, int status);
    }

    private static final class Parcel implements BluetoothRssiCallbacks.ParcelData {
        final List<String> reads = new ArrayList<>();
        final List<Object> values;
        String token = BluetoothRssiCallbacks.DESCRIPTOR;
        int cursor;

        Parcel(Object... values) {
            this.values = Arrays.asList(values);
        }

        @Override public void enforceInterface(String descriptor) {
            reads.add("descriptor");
            if (!descriptor.equals(token)) throw new SecurityException("wrong interface token");
        }

        @Override public int readInt() {
            reads.add("int");
            return (Integer) values.get(cursor++);
        }

        @Override public String readString() {
            reads.add("String");
            return (String) values.get(cursor++);
        }
    }

    private static final class Fixture {
        final boolean local;
        final Object lock = new Object();
        final List<Integer> unregistered = new ArrayList<>();
        final BluetoothRssiCallbacks state =
                new BluetoothRssiCallbacks(lock, ADDRESS, unregistered::add);
        final BluetoothRssiCallbacks.Transactions transactions;
        final BluetoothRssiCallbacks.Dispatch dispatch;
        final Object binder = new Object();
        final LocalCallback callback;

        Fixture(boolean local) {
            this(local, new BluetoothRssiCallbacks.Transactions(31, 47));
        }

        Fixture(boolean local, BluetoothRssiCallbacks.Transactions transactions) {
            this.local = local;
            this.transactions = transactions;
            dispatch = state.dispatch(transactions);
            callback = (LocalCallback) Proxy.newProxyInstance(LocalCallback.class.getClassLoader(),
                    new Class<?>[]{LocalCallback.class}, dispatch.proxyHandler(binder));
        }

        void registered(int status, int id) {
            if (local) callback.onClientRegistered(status, id);
            else assertTrue(dispatch.onTransaction(transactions.registration, new Parcel(status, id)));
        }

        void rssi(String address, int value, int status) {
            if (local) callback.onReadRemoteRssi(address, value, status);
            else assertTrue(dispatch.onTransaction(transactions.rssi, new Parcel(address, value, status)));
        }

        BluetoothRssiCallbacks.Read start() throws Exception {
            registered(0, 17);
            assertTrue(state.awaitRegistration(0));
            BluetoothRssiCallbacks.Read read = state.beginRead();
            assertNotNull(read);
            assertEquals(17, read.clientId);
            return read;
        }
    }

    @Test public void binderAndLocalCallbacksCompleteTheSameProductionRead() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            BluetoothRssiCallbacks.Read read = fixture.start();
            fixture.rssi(ADDRESS, -55, 0);
            assertTrue(read.await(0));
            assertEquals(Integer.valueOf(-55), fixture.state.readResult());
            fixture.state.finishRead();
            fixture.state.close();
            assertEquals(Arrays.asList(17), fixture.unregistered);
        }
    }

    @Test public void actualResolvedLayoutIdsDispatchBeforeTheGenericAcknowledgement() throws Exception {
        for (int sdk : new int[]{29, 30})
            for (Class<?> metadata : new Class<?>[]{
                    BluetoothPlatformSupportTest.CallbackStub.class,
                    BluetoothPlatformSupportTest.ChangedCallbackStub.class,
                    BluetoothPlatformSupportTest.ChangedRegistrationStub.class,
                    BluetoothPlatformSupportTest.ShiftedCallbackStub.class,
                    BluetoothPlatformSupportTest.LastUserTransactionStub.class}) {
            BluetoothPlatformSupport.RssiLayout layout = BluetoothPlatformSupport.verifyRssiLayout(
                    sdk, true, BluetoothPlatformSupportTest.Manager.class,
                    BluetoothPlatformSupportTest.Gatt.class,
                    BluetoothPlatformSupportTest.Uuid.class,
                    BluetoothPlatformSupportTest.Callback.class, metadata);
            Fixture fixture = new Fixture(false, layout.callbackTransactions);
            BluetoothRssiCallbacks.Read read = fixture.start();
            fixture.rssi(ADDRESS, -60, 0);
            assertTrue(read.await(0));
            assertEquals(Integer.valueOf(-60), fixture.state.readResult());
            fixture.state.finishRead();
            fixture.state.close();
        }
    }

    @Test public void binderPreservesParcelArgumentOrderAndDescriptorEnforcement() throws Exception {
        Fixture fixture = new Fixture(false);
        Parcel registration = new Parcel(0, 29);
        assertTrue(fixture.dispatch.onTransaction(31, registration));
        assertEquals(Arrays.asList("descriptor", "int", "int"), registration.reads);
        assertTrue(fixture.state.awaitRegistration(0));
        BluetoothRssiCallbacks.Read read = fixture.state.beginRead();
        assertEquals(29, read.clientId);
        Parcel rssi = new Parcel(ADDRESS, -58, 0);
        assertTrue(fixture.dispatch.onTransaction(47, rssi));
        assertEquals(Arrays.asList("descriptor", "String", "int", "int"), rssi.reads);
        assertTrue(read.await(0));
        assertEquals(Integer.valueOf(-58), fixture.state.readResult());
    }

    @Test public void aWrongBinderDescriptorDoesNotCompleteRegistrationOrRssi() throws Exception {
        Fixture fixture = new Fixture(false);
        Parcel registration = new Parcel(0, 17);
        registration.token = "wrong";
        assertThrows(SecurityException.class, () -> fixture.dispatch.onTransaction(31, registration));
        assertFalse(fixture.state.awaitRegistration(0));
        BluetoothRssiCallbacks.Read read = fixture.start();
        Parcel rssi = new Parcel(ADDRESS, -55, 0);
        rssi.token = "wrong";
        assertThrows(SecurityException.class, () -> fixture.dispatch.onTransaction(47, rssi));
        assertFalse(read.await(0));
        assertNull(fixture.state.readResult());
    }

    @Test public void wrongOrNullMacCannotCompleteAPendingReadButCaseInsensitiveMatchCan()
            throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            BluetoothRssiCallbacks.Read read = fixture.start();
            fixture.rssi("00:11:22:33:44:55", -30, 0);
            fixture.rssi(null, -30, 0);
            assertFalse(read.await(0));
            assertNull(fixture.state.readResult());
            fixture.rssi("aa:bb:cc:dd:ee:ff", -55, 0);
            assertTrue(read.await(0));
            assertEquals(Integer.valueOf(-55), fixture.state.readResult());
        }
    }

    @Test public void invalidMatchingRssiCompletesWithNullInsteadOfTimingOut() throws Exception {
        for (boolean local : new boolean[]{false, true})
            for (int[] sample : new int[][]{{-128, 0}, {21, 0}, {-55, 1}, {0, -1}}) {
                Fixture fixture = new Fixture(local);
                BluetoothRssiCallbacks.Read read = fixture.start();
                fixture.rssi(ADDRESS, sample[0], sample[1]);
                assertTrue(read.await(0));
                assertNull(fixture.state.readResult());
            }
    }

    @Test public void bothInclusiveRawRssiBoundariesRemainValid() throws Exception {
        for (boolean local : new boolean[]{false, true})
            for (int value : new int[]{-127, 20}) {
                Fixture fixture = new Fixture(local);
                BluetoothRssiCallbacks.Read read = fixture.start();
                fixture.rssi(ADDRESS, value, 0);
                assertTrue(read.await(0));
                assertEquals(Integer.valueOf(value), fixture.state.readResult());
            }
    }

    @Test public void failedOrNonpositiveRegistrationNeverCreatesAReadClient() throws Exception {
        for (boolean local : new boolean[]{false, true})
            for (int[] registration : new int[][]{{1, 17}, {0, 0}, {0, -1}}) {
                Fixture fixture = new Fixture(local);
                fixture.registered(registration[0], registration[1]);
                assertFalse(fixture.state.awaitRegistration(0));
                assertFalse(fixture.state.isRegistered());
                assertNull(fixture.state.beginRead());
                fixture.state.close();
                assertTrue(fixture.unregistered.isEmpty());
            }
    }

    @Test public void noPendingReadCannotPrimeTheNextResult() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            fixture.registered(0, 17);
            fixture.rssi(ADDRESS, -20, 0);
            assertNull(fixture.state.readResult());
            BluetoothRssiCallbacks.Read read = fixture.state.beginRead();
            assertFalse(read.await(0));
            fixture.rssi(ADDRESS, -55, 0);
            assertTrue(read.await(0));
            fixture.state.finishRead();
            fixture.rssi(ADDRESS, -10, 0);
            assertEquals(Integer.valueOf(-55), fixture.state.readResult());
            BluetoothRssiCallbacks.Read next = fixture.state.beginRead();
            assertFalse(next.await(0));
            assertNull(fixture.state.readResult());
        }
    }

    @Test public void closeReleasesAReadAndLateRssiCannotReviveIt() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            BluetoothRssiCallbacks.Read read = fixture.start();
            fixture.state.close();
            assertTrue(read.await(0));
            fixture.rssi(ADDRESS, -10, 0);
            assertNull(fixture.state.readResult());
            assertNull(fixture.state.beginRead());
            assertFalse(fixture.state.isRegistered());
            fixture.state.close();
            assertEquals(Arrays.asList(17), fixture.unregistered);
        }
    }

    @Test public void timeoutThenClosurePreventsAnOldCallbackSatisfyingAFutureRead() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            BluetoothRssiCallbacks.Read read = fixture.start();
            assertFalse(read.await(1));
            fixture.state.close(); // The reader's timeout path closes this state before another read.
            fixture.state.finishRead();
            fixture.rssi(ADDRESS, -10, 0);
            assertNull(fixture.state.readResult());
            assertNull(fixture.state.beginRead());
            assertEquals(Arrays.asList(17), fixture.unregistered);
        }
    }

    @Test public void callbackBlockedByCloseCannotRestoreAResult() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            BluetoothRssiCallbacks.Read read = fixture.start();
            CallbackThread callback;
            synchronized (fixture.lock) {
                callback = new CallbackThread(() -> fixture.rssi(ADDRESS, -10, 0));
                callback.thread.start();
                assertTrue("Callback state changes must use the reader's shared lock",
                        blockedOn(callback.thread, fixture.lock));
                fixture.state.close();
                assertTrue(read.await(0));
            }
            callback.join();
            assertNull(fixture.state.readResult());
            assertEquals(Arrays.asList(17), fixture.unregistered);
        }
    }

    @Test public void callbackBlockedDuringReadReplacementCannotCompleteTheNewRead() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            BluetoothRssiCallbacks.Read previous = fixture.start();
            BluetoothRssiCallbacks.Read current;
            CallbackThread callback;
            synchronized (fixture.lock) {
                callback = new CallbackThread(() -> fixture.rssi(ADDRESS, -80, 0));
                callback.thread.start();
                assertTrue(blockedOn(callback.thread, fixture.lock));
                fixture.rssi(ADDRESS, -55, 0);
                assertTrue(previous.await(0));
                fixture.state.finishRead();
                current = fixture.state.beginRead();
                assertNotNull(current);
            }
            callback.join();
            assertFalse("An old callback must not release a later read", current.await(0));
            assertNull(fixture.state.readResult());
            fixture.rssi(ADDRESS, -20, 0);
            assertTrue(current.await(0));
            assertEquals(Integer.valueOf(-20), fixture.state.readResult());
        }
    }

    @Test public void successfulRegistrationAfterClosureIsImmediatelyUnregistered() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(local);
            fixture.state.close();
            fixture.registered(0, 29);
            assertEquals(Arrays.asList(29), fixture.unregistered);
            assertFalse(fixture.state.awaitRegistration(0));
            assertFalse(fixture.state.isRegistered());
        }
    }

    @Test public void closureBeforeOrAfterRegistrationPreservesClientCleanup() throws Exception {
        for (boolean local : new boolean[]{false, true}) {
            Fixture before = new Fixture(local);
            before.state.close();
            before.registered(0, 17);
            Fixture after = new Fixture(local);
            after.registered(0, 17);
            after.state.close();
            assertEquals(Arrays.asList(17), before.unregistered);
            assertEquals(Arrays.asList(17), after.unregistered);
        }
    }

    @Test public void oneReaderStillRejectsOverlappingReads() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.start();
        assertThrows(IllegalStateException.class, fixture.state::beginRead);
        fixture.state.finishRead();
        assertNotNull(fixture.state.beginRead());
    }

    @Test public void malformedDirectCallbacksCannotInventSuccessOrZeroRssi() throws Throwable {
        Fixture fixture = new Fixture(true);
        InvocationHandler handler = fixture.dispatch.proxyHandler(fixture.binder);
        Method registration = LocalCallback.class.getMethod("onClientRegistered", int.class, int.class);
        for (Object[] args : new Object[][]{null, {}, {0}, {0, null}, {0L, 17}, {0, 17, 1}})
            handler.invoke(fixture.callback, registration, args);
        assertFalse(fixture.state.awaitRegistration(0));
        BluetoothRssiCallbacks.Read read = fixture.start();
        Method rssi = LocalCallback.class.getMethod("onReadRemoteRssi", String.class, int.class, int.class);
        for (Object[] args : new Object[][]{null, {}, {ADDRESS}, {ADDRESS, null, 0},
                {ADDRESS, -55, null}, {ADDRESS, -55L, 0}, {17, -55, 0},
                {ADDRESS, -55, 0, 1}})
            handler.invoke(fixture.callback, rssi, args);
        Method wrongSignature = WrongCallback.class.getMethod("onReadRemoteRssi",
                String.class, long.class, int.class);
        handler.invoke(fixture.callback, wrongSignature, new Object[]{ADDRESS, -55, 0});
        assertFalse(read.await(0));
        assertNull(fixture.state.readResult());
    }

    @Test public void binderAndObjectMethodsKeepTheirExistingProxySemantics() throws Exception {
        Fixture fixture = new Fixture(true);
        assertSame(fixture.binder, fixture.callback.asBinder());
        assertEquals(System.identityHashCode(fixture.callback), fixture.callback.hashCode());
        assertEquals("OverDriveConnectedSignalCallback", fixture.callback.toString());
        assertEquals(fixture.callback, fixture.callback);
        assertNotEquals(fixture.callback, new Object());
        fixture.callback.unrelated("ignored");
        assertFalse(fixture.state.awaitRegistration(0));
        Parcel unrelated = new Parcel();
        assertTrue(fixture.dispatch.onTransaction(3, unrelated));
        assertEquals(Arrays.asList("descriptor"), unrelated.reads);
        assertFalse(fixture.dispatch.onTransaction(0x5f4e5446, new Parcel()));
        assertFalse(fixture.dispatch.onTransaction(100, new Parcel()));
    }

    private static final class CallbackThread {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;

        CallbackThread(Runnable callback) {
            thread = new Thread(() -> {
                try { callback.run(); }
                catch (Throwable error) { failure.set(error); }
            }, "rssi-callback-test");
            thread.setDaemon(true);
        }

        void join() throws Exception {
            thread.join(2_000);
            assertFalse("The callback must finish after the state lock is released", thread.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    private static boolean blockedOn(Thread thread, Object lock) {
        try {
            Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
            Object threads = mfClass.getMethod("getThreadMXBean").invoke(null);
            Method getThreadInfo = threads.getClass().getMethod("getThreadInfo", long.class);
            long deadline = System.nanoTime() + 2_000_000_000L;
            do {
                Object info = getThreadInfo.invoke(threads, thread.getId());
                if (info == null) return false;
                Method getLockInfo = info.getClass().getMethod("getLockInfo");
                Object waiting = getLockInfo.invoke(info);
                Method getThreadState = info.getClass().getMethod("getThreadState");
                Thread.State state = (Thread.State) getThreadState.invoke(info);
                if (state == Thread.State.BLOCKED && waiting != null) {
                    Method getIdentityHashCode = waiting.getClass().getMethod("getIdentityHashCode");
                    int hash = (int) getIdentityHashCode.invoke(waiting);
                    if (hash == System.identityHashCode(lock)) return true;
                }
                Thread.yield();
            } while (System.nanoTime() < deadline);
        } catch (Throwable t) {
            return false;
        }
        return false;
    }
}

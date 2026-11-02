package com.overdrive.app.safekeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runners.MethodSorters;

/** Runs actual start/poll/cancel paths; only collaborators provide dummy I/O. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LinkManagerCancellationTest {
    private static final String START_RESPONSE = "{\"device_code\":\"DUMMY_DEVICE_CODE\",\"user_code\":\"DUMMY_USER_CODE\",\"verification_uri\":\"https://dummy.invalid/verify\",\"expires_in\":600,\"interval\":5}";
    private static final String TOKEN_RESPONSE = "{\"access_token\":\"DUMMY_ACCESS_TOKEN\",\"refresh_token\":\"DUMMY_REFRESH_TOKEN\",\"expires_in\":3600}";
    private static volatile Fixture current;

    @Rule public TestName testName = new TestName();
    private Fixture fixture;
    private ObservedScheduler scheduler;
    private ExecutorService canceller;
    private LinkManager manager;

    /** Used solely by the isolated HttpFactory collaborator. */
    public static OkHttpClient client() { return current.client; }

    static void recordStorage(String operation, JSONObject secrets) {
        if (secrets != null) {
            assertEquals("DUMMY_ACCESS_TOKEN", secrets.optString("accessToken"));
            assertEquals("DUMMY_REFRESH_TOKEN", secrets.optString("refreshToken"));
        }
        synchronized (current.storage) { current.storage.add(operation); }
        current.event("STORAGE " + operation + " cancelReturned=" + current.cancelReturned);
    }

    @Before public void setUp() {
        fixture = new Fixture(testName.getMethodName());
        current = fixture;
        fixture.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            assertEquals("POST", request.method());
            assertEquals("https", request.url().scheme());
            assertEquals("oauth2.googleapis.com", request.url().host());
            ResponseBody body;
            if ("/device/code".equals(request.url().encodedPath())) {
                fixture.startRequests.incrementAndGet();
                fixture.event("INTERCEPT dummy start response; transportUnused=true");
                body = ResponseBody.create(MediaType.get("application/json"), START_RESPONSE);
            } else if ("/token".equals(request.url().encodedPath())) {
                fixture.pollRequests.incrementAndGet();
                fixture.event("INTERCEPT dummy poll response; transportUnused=true");
                body = fixture.pauseBody ? pausedBody() : ResponseBody.create(MediaType.get("application/json"), TOKEN_RESPONSE);
            } else {
                throw new AssertionError("Unexpected source URL path");
            }
            // Never proceed to DNS, sockets or a real provider.
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("DUMMY_LOCAL_RESPONSE").body(body).build();
        }).build();
        scheduler = new ObservedScheduler(fixture);
        canceller = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "test-cancel");
            thread.setDaemon(true);
            return thread;
        });
        manager = new LinkManager(new Engine(scheduler));
    }

    @After public void tearDown() throws Exception {
        // Release the callback even if an assertion fails or cancellation blocks.
        fixture.resumeBody.countDown();
        canceller.shutdownNow();
        scheduler.shutdownNow();
        assertTrue("cancellation worker stopped", canceller.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue("poll worker stopped", scheduler.awaitTermination(2, TimeUnit.SECONDS));
        fixture.client.connectionPool().evictAll();
        fixture.client.dispatcher().executorService().shutdownNow();
        current = null;
    }

    @Test(timeout = 20_000L) public void aNormalCompletionPersistsDestination() throws Exception {
        String id = start();
        awaitCallback();
        assertEquals("done", manager.status(id).getString("state"));
        assertEquals(1, fixture.startRequests.get());
        assertEquals(1, fixture.pollRequests.get());
        assertEquals(Arrays.asList("secrets.put", "admin.addDestination"), fixture.storageCalls());
        fixture.event("CONTROL_PASS normal completion");
    }

    @Test(timeout = 20_000L) public void bCancelBeforeCallbackPreventsStorage() throws Exception {
        String id = start();
        assertEquals(0, fixture.pollRequests.get());
        cancelAndAwait(id);
        assertTrue(scheduler.poller.isCancelled());
        // A scheduler sentinel proves the original 5s callback deadline has passed.
        CountDownLatch pastDeadline = new CountDownLatch(1);
        scheduler.schedule(pastDeadline::countDown, 5500, TimeUnit.MILLISECONDS);
        await(pastDeadline, "original callback deadline passed");
        assertEquals("expired", manager.status(id).getString("state"));
        assertEquals(1, fixture.startRequests.get());
        assertEquals(0, fixture.pollRequests.get());
        assertEquals(Collections.emptyList(), fixture.storageCalls());
        fixture.event("CONTROL_PASS cancellation before callback");
    }

    @Test(timeout = 20_000L) public void cCancelDuringResponsePreventsStorage() throws Exception {
        fixture.pauseBody = true;
        String id = start();
        await(fixture.bodyPaused, "poll response body paused");
        assertEquals(Collections.emptyList(), fixture.storageCalls());
        cancelAndAwait(id);
        assertTrue(scheduler.poller.isCancelled());
        assertEquals("expired", manager.status(id).getString("state"));
        fixture.event("STATUS_AFTER_CANCEL expired; resume response");
        fixture.resumeBody.countDown();
        awaitCallback();
        assertEquals(1, fixture.startRequests.get());
        assertEquals(1, fixture.pollRequests.get());
        assertEquals("expired", manager.status(id).getString("state"));
        assertEquals("cancel(id) returned before the response resumed; storage must remain untouched",
                Collections.emptyList(), fixture.storageCalls());
    }

    private String start() throws Exception {
        JSONObject params = new JSONObject().put("app", new JSONObject().put("clientId", "DUMMY_CLIENT_ID"));
        JSONObject state = manager.start("gdrive", params, System.currentTimeMillis() + 10_000L);
        assertEquals("pending", state.getString("state"));
        fixture.event("START_RETURNED pending");
        return state.getString("linkId");
    }

    private void cancelAndAwait(String id) throws Exception {
        fixture.event("CANCEL_CALL");
        // Bound this wait to catch a future fix holding a commit lock during network I/O.
        canceller.submit(() -> manager.cancel(id)).get(2, TimeUnit.SECONDS);
        fixture.cancelReturned = true;
        fixture.event("CANCEL_RETURNED");
    }

    private void awaitCallback() throws Exception {
        await(fixture.callbackEnded, "poll callback completed");
        assertNull("callback must not fail independently of the cancellation assertion", fixture.callbackFailure);
    }

    private static void await(CountDownLatch latch, String reason) throws InterruptedException {
        assertTrue("Timed out: " + reason, latch.await(10, TimeUnit.SECONDS));
    }

    private ResponseBody pausedBody() {
        Buffer buffer = new Buffer().writeUtf8(TOKEN_RESPONSE);
        BufferedSource source = Okio.buffer(new Source() {
            boolean first = true;
            @Override public long read(Buffer sink, long count) throws IOException {
                if (first) {
                    first = false;
                    fixture.event("POLL_BODY_PAUSED");
                    fixture.bodyPaused.countDown();
                    try { await(fixture.resumeBody, "response resumed"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    fixture.event("POLL_BODY_RESUMED cancelReturned=" + fixture.cancelReturned);
                }
                return buffer.read(sink, count);
            }
            @Override public Timeout timeout() { return Timeout.NONE; }
            @Override public void close() { buffer.close(); }
        });
        return new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json"); }
            @Override public long contentLength() { return TOKEN_RESPONSE.getBytes(StandardCharsets.UTF_8).length; }
            @Override public BufferedSource source() { return source; }
        };
    }

    private static final class Fixture {
        final String name;
        final List<String> storage = new ArrayList<>();
        final CountDownLatch bodyPaused = new CountDownLatch(1);
        final CountDownLatch resumeBody = new CountDownLatch(1);
        final CountDownLatch callbackEnded = new CountDownLatch(1);
        final AtomicInteger startRequests = new AtomicInteger();
        final AtomicInteger pollRequests = new AtomicInteger();
        volatile boolean pauseBody;
        volatile boolean cancelReturned;
        volatile Throwable callbackFailure;
        OkHttpClient client;
        Fixture(String name) { this.name = name; }
        List<String> storageCalls() {
            synchronized (storage) { return new ArrayList<>(storage); }
        }
        synchronized void event(String event) { System.out.println(name + " " + event); }
    }

    private static final class ObservedScheduler extends ScheduledThreadPoolExecutor {
        final Fixture fixture;
        volatile ScheduledFuture<?> poller;
        ObservedScheduler(Fixture fixture) {
            super(1, task -> {
                Thread thread = new Thread(task, "test-poll");
                thread.setDaemon(true);
                return thread;
            });
            this.fixture = fixture;
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                long initialDelay, long delay, TimeUnit unit) {
            fixture.event("SCHEDULE initialMs=" + unit.toMillis(initialDelay) + " delayMs=" + unit.toMillis(delay));
            // Observe only; preserve requested delays and the actual JDK Future.
            poller = super.scheduleWithFixedDelay(() -> {
                fixture.event("CALLBACK_BEGIN");
                try { command.run(); }
                catch (Throwable failure) {
                    fixture.callbackFailure = failure;
                    throw new AssertionError("Callback failed", failure);
                } finally {
                    fixture.event("CALLBACK_END");
                    fixture.callbackEnded.countDown();
                }
            }, initialDelay, delay, unit);
            return poller;
        }
    }
}

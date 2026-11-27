package com.overdrive.app.navmap.cluster;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;

import com.overdrive.app.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Daemon-side dispatch through the app's OEM cluster service binding. */
final class ClusterCommDispatcher {
    private ClusterCommDispatcher() {}

    static final class Reply {
        final boolean dispatched, answered;
        final int code, sent;
        final String token, failure;

        Reply(boolean dispatched, boolean answered, int code, String token, int sent, String failure) {
            this.dispatched = dispatched;
            this.answered = answered;
            this.code = code;
            this.token = token == null ? "" : token;
            this.sent = sent;
            this.failure = failure;
        }

        boolean ok() { return answered && code == ClusterCommContract.RESULT_SENT; }
        boolean maybeDelivered() { return ok() || dispatched && (!answered || sent > 0); }

        boolean definitive() {
            return answered && (code == ClusterCommContract.RESULT_SERVICE_UNAVAILABLE
                    || code == ClusterCommContract.RESULT_BIND_DENIED
                    || code == ClusterCommContract.RESULT_UNSUPPORTED_INTERFACE);
        }

        String name() { return answered ? ClusterCommContract.resultName(code) : failure; }
    }

    static Reply dispatch(Context context, List<Bundle> commands, long budgetMs,
            String instrumentOwner) {
        if (context == null) return new Reply(false, false, -1, "", 0, "daemon_context_unavailable");
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Reply> answer = new AtomicReference<>();
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                answer.compareAndSet(null, new Reply(true, true, code,
                        data == null ? "" : data.getString(ClusterCommContract.REPLY_TOKEN, ""),
                        data == null ? 0 : data.getInt(ClusterCommContract.REPLY_SENT, 0), ""));
                done.countDown();
            }
        };
        Intent intent = new Intent(ClusterCommContract.ACTION)
                .setComponent(new ComponentName(BuildConfig.APPLICATION_ID, ClusterCommContract.RECEIVER))
                // BYD's power service force-stops the app at ACC-off; stopped apps get no broadcasts.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ClusterCommContract.EXTRA_DEADLINE, deadline)
                .putExtra(ClusterCommContract.EXTRA_REPLY, receiver);
        if (instrumentOwner == null) {
            intent.putParcelableArrayListExtra(ClusterCommContract.EXTRA_COMMANDS,
                    new ArrayList<>(commands));
        } else {
            intent.putExtra(ClusterCommContract.EXTRA_INSTRUMENT_OWNER, instrumentOwner);
        }
        try {
            context.sendBroadcast(intent);
        } catch (SecurityException denied) {
            return new Reply(false, false, -1, "", 0, "broadcast_denied");
        } catch (Throwable failure) {
            return new Reply(false, false, -1, "", 0, "broadcast_failed");
        }
        try {
            if (done.await(budgetMs + 1_000L, TimeUnit.MILLISECONDS)) return answer.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Reply(true, false, -1, "", 0, "app_unavailable");
    }

}

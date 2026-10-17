#include "fast_cam_ipc.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

typedef ssize_t (*send_fn)(int, const void*, size_t, int);
typedef ssize_t (*sendmsg_fn)(int, const struct msghdr*, int);
typedef void (*guard_handler_t)(int);
typedef guard_handler_t (*signal_fn)(int, guard_handler_t);

enum { FAST_CAM_RELEASE_TIMEOUT_MS = 3000 };
/* Once a stop is requested, stop waiting on a consumer that is shutting down. */
enum { FAST_CAM_STOPPING_RELEASE_TIMEOUT_MS = 250 };

/*
 * Durable record of WHY the guard gave up on the consumer. Must match
 * DiLink5QCarCamBackend.RELEASE_GUARD_REASON_PATH, which logs and consumes
 * the file when it classifies the child's exit. The daemon log gets
 * truncated in place (log_2MEH8B86 lost the initiating exit-125 cause that
 * way); this file survives truncation, and the stderr copy reaches the
 * daemon's [FastCamProc] output drainer while it is still attached.
 * The guard only runs in DiLink 5 camera mode, where daemon storage lives in
 * the shell user's data dir; shell cannot create files in /data/local/tmp
 * there (SELinux).
 */
static const char REASON_FILE_PATH[] =
        "/data/user_de/0/com.android.shell/overdrive/"
        "overdrive_dilink5_release_guard_reason";

static send_fn real_send;
static sendmsg_fn real_sendmsg;
static signal_fn real_signal;
static pthread_once_t symbol_once = PTHREAD_ONCE_INIT;
static atomic_int camera_socket = ATOMIC_VAR_INIT(-1);

/*
 * fast_cam_capture installs one SIGINT/SIGTERM handler that clears its
 * capture-loop flag; the loop then takes the same exit as --time expiry:
 * QCarCam streams stopped and closed, "[+] Done.", exit 0. Abrupt exit skips
 * that, and on SA8155P the QNX-hosted camera service can keep DMA-ing into
 * the freed ION buffers (suspected cause of system-wide gralloc metadata
 * corruption -> hwcodec/SurfaceFlinger/HWC crashes -> boot loops). So every
 * consumer failure requests that clean stop instead of exiting.
 */
static _Atomic(guard_handler_t) capture_stop_handler;
static atomic_int stop_requested = ATOMIC_VAR_INIT(0);
static atomic_int consumer_lost = ATOMIC_VAR_INIT(0);

static void resolve_symbols_once(void) {
    real_send = (send_fn)dlsym(RTLD_NEXT, "send");
    real_sendmsg = (sendmsg_fn)dlsym(RTLD_NEXT, "sendmsg");
    real_signal = (signal_fn)dlsym(RTLD_NEXT, "signal");
}

static int resolve_symbols(void) {
    pthread_once(&symbol_once, resolve_symbols_once);
    return real_send && real_sendmsg;
}

/*
 * stdout/stderr are a pipe drained by the daemon. If the daemon dies, the next
 * FPS printf would raise SIGPIPE and kill the process before its clean QCarCam
 * stop; ignore it so writes just fail with EPIPE.
 */
__attribute__((constructor))
static void ignore_sigpipe(void) {
    pthread_once(&symbol_once, resolve_symbols_once);
    if (real_signal) real_signal(SIGPIPE, SIG_IGN);
}

static void guarded_stop_signal(int signal_number) {
    atomic_store(&stop_requested, 1);
    guard_handler_t handler = atomic_load(&capture_stop_handler);
    if (handler) handler(signal_number);
}

__attribute__((visibility("default")))
guard_handler_t signal(int signal_number, guard_handler_t handler) {
    pthread_once(&symbol_once, resolve_symbols_once);
    if (!real_signal) {
        errno = ENOSYS;
        return SIG_ERR;
    }
    if ((signal_number == SIGINT || signal_number == SIGTERM)
            && handler != SIG_DFL && handler != SIG_IGN
            && handler != SIG_ERR) {
        atomic_store(&capture_stop_handler, handler);
        return real_signal(signal_number, guarded_stop_signal);
    }
    return real_signal(signal_number, handler);
}

static int64_t monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int remaining_millis(int64_t deadline) {
    int64_t now = monotonic_millis();
    if (now < 0 || now >= deadline) return 0;
    int64_t remaining = deadline - now;
    return remaining > INT32_MAX ? INT32_MAX : (int)remaining;
}

/*
 * Record the initiating failure before the guard gives up on the consumer.
 * One preformatted line, written best-effort to (a) stderr — merged into
 * stdout by the daemon's redirectErrorStream(true), so the [FastCamProc]
 * drainer logs it — and (b) the reason file, which survives both a detached
 * drainer (daemon shutdown) and daemon-log truncation. No allocation; a
 * fixed stack buffer and raw write()s only, since the process is mid-failure.
 * `frame` is optional (handshake failures have none); `detail` carries the
 * step-specific count (bytes sent/received).
 */
static void record_failure(
        const char* step,
        int saved_errno,
        const fast_cam_frame_msg_t* frame,
        long detail) {
    char line[256];
    int length;
    if (frame) {
        length = snprintf(
                line, sizeof(line),
                "RELEASE_GUARD_FAIL: step=%s errno=%d detail=%ld"
                " cam=%u buf=%u seq=%u uptime_ms=%lld"
                " timeout_ms=%d\n",
                step, saved_errno, detail,
                (unsigned)frame->cam_id,
                (unsigned)frame->buf_index,
                (unsigned)frame->sequence_no,
                (long long)monotonic_millis(),
                FAST_CAM_RELEASE_TIMEOUT_MS);
    } else {
        length = snprintf(
                line, sizeof(line),
                "RELEASE_GUARD_FAIL: step=%s errno=%d detail=%ld"
                " uptime_ms=%lld timeout_ms=%d\n",
                step, saved_errno, detail,
                (long long)monotonic_millis(),
                FAST_CAM_RELEASE_TIMEOUT_MS);
    }
    if (length <= 0) return;
    if (length > (int)sizeof(line) - 1) length = (int)sizeof(line) - 1;

    ssize_t ignored = write(STDERR_FILENO, line, (size_t)length);
    (void)ignored;

    int file_fd = open(
            REASON_FILE_PATH,
            O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC,
            0600);
    if (file_fd >= 0) {
        ignored = write(file_fd, line, (size_t)length);
        (void)ignored;
        close(file_fd);
    }
}

/*
 * The consumer is gone, stuck or out of protocol. Cut it off and ask
 * fast_cam_capture to leave its loop through its own clean QCarCam stop.
 * Returning lets it recycle the in-flight frame to the producer; at worst a
 * consumer still sampling that buffer sees one torn frame, which is far
 * cheaper than an abrupt exit with live streams.
 */
static void request_clean_stop(int socket_fd) {
    atomic_store(&consumer_lost, 1);
    shutdown(socket_fd, SHUT_RDWR);
    guard_handler_t handler = atomic_load(&capture_stop_handler);
    if (!handler) {
        // No handler means an unknown binary; keep the old fail-closed exit.
        _exit(125);
    }
    atomic_store(&stop_requested, 1);
    handler(SIGTERM);
}

/*
 * A stop request (SIGTERM/SIGINT from the daemon) means the consumer is
 * retiring and may stop releasing frames; bound the remaining wait so the
 * capture loop reaches its flag check promptly. Polls are restarted on EINTR,
 * so the shortened deadline applies to a wait already in progress.
 */
static int64_t stopping_deadline(int64_t deadline) {
    if (!atomic_load(&stop_requested)) return deadline;
    static _Atomic int64_t stop_deadline = ATOMIC_VAR_INIT(0);
    int64_t cap = atomic_load(&stop_deadline);
    if (cap == 0) {
        int64_t candidate =
                monotonic_millis() + FAST_CAM_STOPPING_RELEASE_TIMEOUT_MS;
        int64_t expected = 0;
        cap = atomic_compare_exchange_strong(
                &stop_deadline, &expected, candidate)
                ? candidate : expected;
    }
    return cap < deadline ? cap : deadline;
}

static int wait_for_fence(int fence_fd) {
    if (fence_fd < 0) return 0;
    int64_t deadline =
            monotonic_millis() + FAST_CAM_RELEASE_TIMEOUT_MS;
    struct pollfd descriptor = {
        .fd = fence_fd,
        .events = POLLIN,
        .revents = 0,
    };
    int result;
    do {
        deadline = stopping_deadline(deadline);
        int timeout = remaining_millis(deadline);
        if (timeout <= 0) {
            result = 0;
            break;
        }
        result = poll(&descriptor, 1, timeout);
    } while (result < 0 && errno == EINTR);
    close(fence_fd);
    return result > 0 && (descriptor.revents & POLLIN) ? 0 : -1;
}

static int receive_release(
        int socket_fd,
        const fast_cam_frame_msg_t* frame) {
    fast_cam_frame_release_msg_t release = {0};
    size_t total = 0;
    int fence_fd = -1;
    int fence_count = 0;
    int saved_errno = 0;
    int64_t deadline =
            monotonic_millis() + FAST_CAM_RELEASE_TIMEOUT_MS;
    while (total < sizeof(release)) {
        struct pollfd descriptor = {
            .fd = socket_fd,
            .events = POLLIN,
            .revents = 0,
        };
        int ready;
        do {
            deadline = stopping_deadline(deadline);
            int timeout = remaining_millis(deadline);
            if (timeout <= 0) {
                ready = 0;
                break;
            }
            ready = poll(&descriptor, 1, timeout);
        } while (ready < 0 && errno == EINTR);
        if (ready <= 0 || !(descriptor.revents & POLLIN)) {
            saved_errno = ready < 0 ? errno : 0;   // 0 = deadline expired
            break;
        }

        struct iovec iov = {
            .iov_base = (uint8_t*)&release + total,
            .iov_len = sizeof(release) - total,
        };
        char control[CMSG_SPACE(sizeof(int))] = {0};
        struct msghdr message = {0};
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        ssize_t received = recvmsg(
                socket_fd,
                &message,
                MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (received < 0 && (errno == EINTR
                || errno == EAGAIN
                || errno == EWOULDBLOCK)) {
            continue;
        }
        if (received <= 0
                || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC))) {
            saved_errno = received < 0 ? errno : 0;
            break;
        }
        int ancillary_valid = 1;
        for (struct cmsghdr* header = CMSG_FIRSTHDR(&message);
                header;
                header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level == SOL_SOCKET
                    && header->cmsg_type == SCM_RIGHTS) {
                if (header->cmsg_len < CMSG_LEN(sizeof(int))
                        || (header->cmsg_len - CMSG_LEN(0))
                                % sizeof(int) != 0) {
                    ancillary_valid = 0;
                    break;
                }
                int count = (int)(
                        (header->cmsg_len - CMSG_LEN(0)) / sizeof(int));
                int* descriptors = (int*)CMSG_DATA(header);
                for (int i = 0; i < count; i++) {
                    if (fence_count++ == 0) {
                        fence_fd = descriptors[i];
                    } else {
                        close(descriptors[i]);
                    }
                }
            }
        }
        if (!ancillary_valid) break;
        total += (size_t)received;
    }
    if (total != sizeof(release)
            || release.magic != FAST_CAM_MAGIC
            || release.msg_type != FAST_CAM_MSG_FRAME_RELEASE
            || release.cam_id != frame->cam_id
            || release.buf_index != frame->buf_index
            || release.sequence_no != frame->sequence_no
            || fence_count > 1) {
        if (fence_fd >= 0) close(fence_fd);
        // Truncated (timeout/EOF/transport error: detail = bytes received)
        // or a release that does not match the in-flight frame.
        record_failure(
                total != sizeof(release)
                        ? "release-recv" : "release-mismatch",
                saved_errno, frame, (long)total);
        return -1;
    }
    if (wait_for_fence(fence_fd) != 0) {
        // The consumer's release fence never signalled inside the timeout:
        // the importing side may still be reading the DMA-BUF.
        record_failure("release-fence", 0, frame, (long)total);
        return -1;
    }
    return 0;
}

__attribute__((visibility("default")))
ssize_t sendmsg(
        int socket_fd,
        const struct msghdr* message,
        int flags) {
    if (!resolve_symbols()) {
        errno = ENOSYS;
        return -1;
    }
    if (!message
            || message->msg_iovlen != 1
            || message->msg_iov[0].iov_len
                    != sizeof(fast_cam_handshake_multi_resp_t)) {
        return real_sendmsg(socket_fd, message, flags);
    }

    const fast_cam_handshake_multi_resp_t* original =
            (const fast_cam_handshake_multi_resp_t*)
                    message->msg_iov[0].iov_base;
    if (!original
            || original->magic != FAST_CAM_MAGIC
            || original->msg_type != FAST_CAM_MSG_HANDSHAKE_RESP) {
        return real_sendmsg(socket_fd, message, flags);
    }

    fast_cam_handshake_multi_resp_t guarded = *original;
    guarded.msg_type |= FAST_CAM_CAP_FRAME_RELEASE_FENCE;
    struct iovec guarded_iov = {
        .iov_base = &guarded,
        .iov_len = sizeof(guarded),
    };
    struct msghdr guarded_message = *message;
    guarded_message.msg_iov = &guarded_iov;
    guarded_message.msg_iovlen = 1;
    ssize_t sent = real_sendmsg(socket_fd, &guarded_message, flags);
    if (sent != (ssize_t)sizeof(guarded)) {
        record_failure("handshake-send", errno, NULL, (long)sent);
        request_clean_stop(socket_fd);
        return sent;
    }
    atomic_store_explicit(
            &camera_socket, socket_fd, memory_order_release);
    return sent;
}

__attribute__((visibility("default")))
ssize_t send(int socket_fd, const void* data, size_t length, int flags) {
    if (!resolve_symbols()) {
        errno = ENOSYS;
        return -1;
    }
    fast_cam_frame_msg_t frame = {0};
    int guarded_frame =
            data
            && length == sizeof(frame)
            && socket_fd == atomic_load_explicit(
                    &camera_socket, memory_order_acquire);
    if (guarded_frame) {
        memcpy(&frame, data, sizeof(frame));
        guarded_frame =
                frame.magic == FAST_CAM_MAGIC
                && frame.msg_type == FAST_CAM_MSG_FRAME_READY;
    }
    if (guarded_frame && atomic_load(&consumer_lost)) {
        // Already stopping: the socket is shut down, so this fails fast.
        // MSG_NOSIGNAL keeps a SIGPIPE from killing the process before its
        // capture loop reaches the clean QCarCam stop.
        return real_send(socket_fd, data, length, flags | MSG_NOSIGNAL);
    }
    ssize_t sent = real_send(
            socket_fd, data, length,
            guarded_frame ? flags | MSG_NOSIGNAL : flags);
    if (!guarded_frame) return sent;
    if (sent != (ssize_t)sizeof(frame)) {
        record_failure("frame-send", errno, &frame, (long)sent);
        request_clean_stop(socket_fd);
        return sent;
    }
    if (receive_release(socket_fd, &frame) != 0) {
        // receive_release already recorded the specific failure.
        request_clean_stop(socket_fd);
    }
    return sent;
}

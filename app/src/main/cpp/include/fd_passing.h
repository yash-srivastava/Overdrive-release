#pragma once
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>

static inline int send_fds(int sock, const int* fds, int num_fds, const void* data, size_t data_len) {
    struct msghdr msg;
    struct iovec iov;
    char cmsg_buf[CMSG_SPACE(sizeof(int) * num_fds)];

    memset(&msg, 0, sizeof(msg));
    memset(cmsg_buf, 0, sizeof(cmsg_buf));

    iov.iov_base = (void*)data;
    iov.iov_len  = data_len;
    msg.msg_iov  = &iov;
    msg.msg_iovlen = 1;

    if (num_fds > 0) {
        msg.msg_control    = cmsg_buf;
        msg.msg_controllen = sizeof(cmsg_buf);

        struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type  = SCM_RIGHTS;
        cmsg->cmsg_len   = CMSG_LEN(sizeof(int) * num_fds);

        memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * num_fds);
    }

    ssize_t sent = sendmsg(sock, &msg, MSG_NOSIGNAL);
    return (sent >= 0) ? 0 : -1;
}

static inline int recv_fds(int sock, int* fds, int max_fds, int* num_fds_received, void* data, size_t data_len) {
    struct msghdr msg;
    struct iovec iov;
    char cmsg_buf[CMSG_SPACE(sizeof(int) * max_fds)];

    memset(&msg, 0, sizeof(msg));
    memset(cmsg_buf, 0, sizeof(cmsg_buf));

    iov.iov_base = data;
    iov.iov_len  = data_len;
    msg.msg_iov  = &iov;
    msg.msg_iovlen = 1;

    msg.msg_control    = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    ssize_t recvd = recvmsg(sock, &msg, 0);
    if (recvd <= 0) return -1;

    *num_fds_received = 0;
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    if (cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
        int n = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        if (n > max_fds) n = max_fds;
        memcpy(fds, CMSG_DATA(cmsg), sizeof(int) * n);
        *num_fds_received = n;
    }
    return (int)recvd;
}

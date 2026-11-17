package com.overdrive.app.server.hls;

/** An MP4 that cannot be repackaged; the message is a short reason. */
public final class Mp4Exception extends Exception {
    private static final long serialVersionUID = 1L;

    public Mp4Exception(String reason) {
        super(reason);
    }
}

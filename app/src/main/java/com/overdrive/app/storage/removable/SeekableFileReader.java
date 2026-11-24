package com.overdrive.app.storage.removable;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** {@code new RandomAccessFile(file, "r")} for card files too (RandomAccessFile opens by path only). */
public final class SeekableFileReader implements Closeable {
    private final FileInputStream in;
    private final FileChannel channel;
    private final ByteBuffer scratch = ByteBuffer.allocate(8);
    private long pos;

    public SeekableFileReader(File file) throws IOException {
        in = RemovableStorageFs.openInput(file);
        channel = in.getChannel();
    }

    public void seek(long position) {
        pos = position;
    }

    public long length() throws IOException {
        return channel.size();
    }

    /** Like RandomAccessFile.read: up to {@code len} bytes, or -1 at end of file. */
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        int n = channel.read(ByteBuffer.wrap(b, off, len), pos);
        if (n > 0) pos += n;
        return n < 0 ? -1 : n;
    }

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public long getFilePointer() {
        return pos;
    }

    /** The underlying channel (positional; independent of this reader's position). */
    public FileChannel getChannel() {
        return channel;
    }

    public void readFully(byte[] b, int off, int len) throws IOException {
        ByteBuffer dst = ByteBuffer.wrap(b, off, len);
        while (dst.hasRemaining()) {
            int n = channel.read(dst, pos);
            if (n < 0) throw new EOFException();
            pos += n;
        }
    }

    private ByteBuffer read(int n) throws IOException {
        scratch.clear().limit(n);
        while (scratch.hasRemaining()) {
            int r = channel.read(scratch, pos);
            if (r < 0) throw new EOFException();
            pos += r;
        }
        scratch.flip();
        return scratch;
    }

    public int readUnsignedByte() throws IOException {
        return read(1).get() & 0xFF;
    }

    public int readInt() throws IOException {
        return read(4).getInt();
    }

    public long readLong() throws IOException {
        return read(8).getLong();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}

package com.overdrive.app.safekeep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.security.MessageDigest;

public class StreamDigestsTest {

    private static final int BLOCK = 4 * 1024 * 1024;

    // Reference content hashes for bytes i % 251, computed independently as
    // SHA-256 of the concatenated binary SHA-256 digests of each 4 MiB block.
    private static final String EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String BEFORE_BLOCK =
            "e1d05b9adf4293b7fec11b099ce74116fc06dfa733a9833be9166ab4c769da43";
    private static final String EXACT_BLOCK =
            "b9654428408015906b44a00935b70af33830aa344b780b0eabd535a133150d04";
    private static final String PARTIAL_SECOND =
            "d9a07742bea2c63e8f91388ca3fddc91db0070af2b6163e728b2e4fcfe1b1869";
    private static final String PARTIAL_THIRD =
            "72429fae755902bc3ca23f81420f23b1108678c8c2a6141cd35db04d0df522a4";
    private static final String SEVEN_BYTES =
            "8586beccafd1f6c117c4e32daa32ba31e1e800b2afdd6dd9659d4ec766dcfe7f";

    @Test
    public void slicedUpdatesAndRepeatedReadsPreserveBlockBoundaries() throws Exception {
        byte[] data = payload(BLOCK + 3);
        StreamDigests digests = new StreamDigests(true, true, true);
        assertDigests(digests, data, 0, EMPTY);

        digests.update(data, 0, BLOCK - 1);
        assertDigests(digests, data, BLOCK - 1, BEFORE_BLOCK);
        assertDigests(digests, data, BLOCK - 1, BEFORE_BLOCK);

        digests.update(data, BLOCK - 1, 1);
        assertDigests(digests, data, BLOCK, EXACT_BLOCK);
        digests.update(data, BLOCK, 3);
        assertDigests(digests, data, BLOCK + 3, PARTIAL_SECOND);
    }

    @Test
    public void checkpointCanBeRestoredRepeatedlyAfterCrossingAnotherBlock() throws Exception {
        byte[] data = payload(2 * BLOCK + 7);
        StreamDigests digests = new StreamDigests(true, true, true);
        int confirmed = BLOCK + 3;
        digests.update(data, 0, confirmed);
        StreamDigests.Checkpoint checkpoint = digests.checkpoint();
        assertNotNull(checkpoint);

        // An attempted upload crosses the next block boundary before it fails.
        digests.update(data, confirmed, data.length - confirmed);
        assertDigests(digests, data, data.length, PARTIAL_THIRD);
        digests.restore(checkpoint);
        assertDigests(digests, data, confirmed, PARTIAL_SECOND);

        // A second attempt must not mutate the saved checkpoint itself.
        digests.update(data, confirmed, 17);
        digests.restore(checkpoint);
        assertDigests(digests, data, confirmed, PARTIAL_SECOND);
        digests.update(data, confirmed, data.length - confirmed);
        assertDigests(digests, data, data.length, PARTIAL_THIRD);
    }

    @Test
    public void resetClearsBothCompletedAndPartialBlocks() throws Exception {
        byte[] data = payload(BLOCK + 3);
        StreamDigests digests = new StreamDigests(true, true, true);
        digests.update(data, 0, data.length);
        assertDigests(digests, data, data.length, PARTIAL_SECOND);
        digests.reset();
        assertDigests(digests, data, 0, EMPTY);
        digests.update(data, 0, 7);
        assertDigests(digests, data, 7, SEVEN_BYTES);
    }

    @Test
    public void disabledDigestsStillTrackBytes() throws Exception {
        byte[] data = payload(7);
        StreamDigests digests = new StreamDigests(false, false, false);
        digests.update(data, 1, 3);
        StreamDigests.Checkpoint checkpoint = digests.checkpoint();
        assertNotNull(checkpoint);
        digests.update(data, 4, 3);
        assertEquals(6L, digests.fedBytes());
        digests.restore(checkpoint);
        assertEquals(3L, digests.fedBytes());
        assertNull(digests.md5Hex());
        assertNull(digests.sha256Hex());
        assertNull(digests.dropboxHex());
        digests.reset();
        assertEquals(0L, digests.fedBytes());
    }

    private static byte[] payload(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (i % 251);
        return data;
    }

    private static void assertDigests(StreamDigests digests, byte[] data, int length,
                                      String dropbox) throws Exception {
        assertEquals((long) length, digests.fedBytes());
        assertEquals(referenceHex("MD5", data, length), digests.md5Hex());
        assertEquals(referenceHex("SHA-256", data, length), digests.sha256Hex());
        assertEquals(dropbox, digests.dropboxHex());
    }

    private static String referenceHex(String algorithm, byte[] data, int length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        digest.update(data, 0, length);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(b & 0x0f, 16));
        }
        return hex.toString();
    }
}

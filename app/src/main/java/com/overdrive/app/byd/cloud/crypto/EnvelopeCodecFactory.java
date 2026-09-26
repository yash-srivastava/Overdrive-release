package com.overdrive.app.byd.cloud.crypto;

import android.content.Context;

import java.io.InputStream;

/**
 * Region-aware factory for the transport envelope codec and its lookup tables.
 *
 * One switch point so every BydCloudClient construction / table-extraction site
 * stays region-agnostic:
 *   - China  ({@code isChina == true})  → {@link WbskCodec}    + {@link WbskTablesFile}
 *   - others ({@code isChina == false}) → {@link BangcleCodec} + {@link BangcleTablesFile}
 *
 * Overseas behavior is unchanged: callers that pass {@code isChina == false} get
 * exactly the Bangcle codec + asset they got before.
 */
public final class EnvelopeCodecFactory {

    private EnvelopeCodecFactory() {}

    /** Create the codec implementation for the region. */
    public static EnvelopeCodec createCodec(boolean isChina) {
        return isChina ? new WbskCodec() : new BangcleCodec();
    }

    /**
     * Open a validated stream over the white-box tables for the region.
     * Returns null if neither the disk cache nor the APK asset is usable.
     */
    public static InputStream openTablesStream(boolean isChina, Context ctx) {
        return isChina
                ? WbskTablesFile.openStream(ctx)
                : BangcleTablesFile.openStream(ctx);
    }

    /** Whether a valid cached table file already exists for the region. */
    public static boolean isCacheValid(boolean isChina) {
        return isChina
                ? WbskTablesFile.isValid(new java.io.File(WbskTablesFile.cachePath()))
                : BangcleTablesFile.isValid(new java.io.File(BangcleTablesFile.cachePath()));
    }

    /** Human-readable cache description for logs. */
    public static String describeCache(boolean isChina) {
        return isChina ? WbskTablesFile.describeCache() : BangcleTablesFile.describeCache();
    }
}

package com.overdrive.app.parking.signage;

/** Max-score stitching with the detector's original nearest-neighbor output coordinates. */
final class OcrTileMerger {
    private final int outputWidth;
    private final int[] columns;
    private final int[] rows;

    OcrTileMerger(int detW, int detH, int ow, int oh) {
        outputWidth = ow;
        // The mapping is tile-local and identical for every tile in this fine pass.
        columns = detW == ow ? null : new int[detW];
        rows = detH == oh ? null : new int[detH];
        if (columns != null) {
            for (int x = 0; x < detW; x++) columns[x] = Math.min(ow - 1, x * ow / detW);
        }
        if (rows != null) {
            for (int y = 0; y < detH; y++) rows[y] = Math.min(oh - 1, y * oh / detH) * ow;
        }
    }

    /** Merge only the real (non-padding) pixels, keeping the prior score on ties or NaN. */
    void merge(float[] tile, float[] map, int mapWidth, int tx, int ty, int tw, int th) {
        for (int y = 0; y < th; y++) {
            int src = rows == null ? y * outputWidth : rows[y];
            int dst = (ty + y) * mapWidth + tx;
            if (columns == null) {
                for (int x = 0; x < tw; x++) {
                    float v = tile[src + x];
                    if (v > map[dst + x]) map[dst + x] = v;
                }
            } else {
                for (int x = 0; x < tw; x++) {
                    float v = tile[src + columns[x]];
                    if (v > map[dst + x]) map[dst + x] = v;
                }
            }
        }
    }
}

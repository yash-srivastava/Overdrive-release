package com.overdrive.app.server.hls;

/** One indexed track. Times are output decode times in track ticks, already shifted by the edit list. */
final class Mp4Track {
    final boolean video;
    final long timescale;
    final byte[] stsd;
    final String codec;
    final int count;
    /** time[i] = decode time of sample i; time[count] = track end. Never negative. */
    final long[] time;
    final int[] size;
    final long[] offset;
    /** Composition offsets, or null when every sample has pts == dts. */
    final int[] cts;
    /** Ticks added to every time to keep it non-negative; subtract for presentation time. */
    final long baseTicks;
    /** media_time written to the init's elst (audio priming); 0 when none. */
    final long editMediaTime;
    final int tkhdFlags;
    final int layer;
    final int altGroup;
    final int volume;
    final byte[] matrix;
    final long tkhdWidth;
    final long tkhdHeight;
    final int language;
    final int width;
    final int height;

    Mp4Track(boolean video, long timescale, byte[] stsd, String codec, long[] time, int[] size,
             long[] offset, int[] cts, long baseTicks, long editMediaTime, int tkhdFlags, int layer, int altGroup,
             int volume, byte[] matrix, long tkhdWidth, long tkhdHeight, int language,
             int width, int height) {
        this.video = video;
        this.timescale = timescale;
        this.stsd = stsd;
        this.codec = codec;
        this.count = size.length;
        this.time = time;
        this.size = size;
        this.offset = offset;
        this.cts = cts;
        this.baseTicks = baseTicks;
        this.editMediaTime = editMediaTime;
        this.tkhdFlags = tkhdFlags;
        this.layer = layer;
        this.altGroup = altGroup;
        this.volume = volume;
        this.matrix = matrix;
        this.tkhdWidth = tkhdWidth;
        this.tkhdHeight = tkhdHeight;
        this.language = language;
        this.width = width;
        this.height = height;
    }

    long duration(int i) {
        return time[i + 1] - time[i];
    }

    int ctsAt(int i) {
        return cts == null ? 0 : cts[i];
    }

    long toUs(long ticks) {
        return Mp4Index.ticksToUs(ticks, timescale);
    }
}

class CabinAudioPlaybackProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.buffer = new Float32Array(48000);
        this.readIndex = 0;
        this.writeIndex = 0;
        this.size = 0;
        this.primed = false;
        this.port.onmessage = (event) => {
            if (event.data && event.data.type === 'reset') {
                this.readIndex = 0;
                this.writeIndex = 0;
                this.size = 0;
                this.primed = false;
                return;
            }
            if (event.data instanceof ArrayBuffer) this.push(event.data);
        };
    }

    push(raw) {
        const pcm = new Int16Array(raw);
        const capacity = this.buffer.length;
        const start = Math.max(0, pcm.length - capacity);
        const incoming = pcm.length - start;
        const overflow = Math.max(0, this.size + incoming - capacity);
        this.readIndex = (this.readIndex + overflow) % capacity;
        this.size -= overflow;
        // Copy at most two contiguous spans; wrap and update counters once
        // per packet rather than once per PCM sample.
        const first = Math.min(incoming, capacity - this.writeIndex);
        const write = this.writeIndex;
        const buffer = this.buffer;
        for (let i = 0; i < first; i++) {
            buffer[write + i] = pcm[start + i] / 32768;
        }
        const remaining = incoming - first;
        for (let i = 0; i < remaining; i++) {
            buffer[i] = pcm[start + first + i] / 32768;
        }
        this.writeIndex = (write + incoming) % capacity;
        this.size += incoming;
    }

    process(inputs, outputs) {
        const channels = outputs[0];
        if (!channels || !channels.length) return true;
        const output = channels[0];
        if (!this.primed && this.size >= 3840) this.primed = true;
        const count = this.primed ? Math.min(this.size, output.length) : 0;
        if (count > 0) {
            const buffer = this.buffer;
            const read = this.readIndex;
            const first = Math.min(count, buffer.length - read);
            // Avoid allocating temporary typed-array views in the audio callback.
            for (let i = 0; i < first; i++) {
                output[i] = buffer[read + i];
            }
            for (let i = first; i < count; i++) {
                output[i] = buffer[i - first];
            }
            this.readIndex = (read + count) % buffer.length;
            this.size -= count;
        }
        if (count < output.length) {
            output.fill(0, count);
            // Preserve the exact-drain behaviour: a full quantum leaves the
            // processor primed until a later quantum actually needs silence.
            if (this.size === 0) this.primed = false;
        }
        for (let channel = 1; channel < channels.length; channel++) {
            channels[channel].set(output);
        }
        return true;
    }
}

registerProcessor('cabin-audio-playback', CabinAudioPlaybackProcessor);

class RemoteVoiceCaptureProcessor extends AudioWorkletProcessor {
    constructor(options) {
        super();
        var processorOptions =
            options && options.processorOptions
                ? options.processorOptions : {};
        this.targetSampleRate =
            processorOptions.targetSampleRate || 16000;
        this.blockSize = processorOptions.blockSize || 2048;
        this.block = new Float32Array(this.blockSize);
        this.blockOffset = 0;
    }

    process(inputs) {
        var input = inputs[0];
        var channel = input && input[0];
        if (!channel || channel.length === 0) return true;

        var sourceOffset = 0;
        while (sourceOffset < channel.length) {
            var available = this.blockSize - this.blockOffset;
            var count = Math.min(available, channel.length - sourceOffset);
            this.block.set(
                channel.subarray(sourceOffset, sourceOffset + count),
                this.blockOffset);
            this.blockOffset += count;
            sourceOffset += count;
            if (this.blockOffset === this.blockSize) {
                this.emitBlock();
                this.blockOffset = 0;
            }
        }
        return true;
    }

    emitBlock() {
        var outputRate = Math.min(this.targetSampleRate, sampleRate);
        var ratio = sampleRate / outputRate;
        var outputLength =
            Math.max(1, Math.floor(this.block.length / ratio));
        var pcm = new Int16Array(outputLength);
        var sourceOffset = 0;
        var squareSum = 0;

        if (ratio === 1 && this.block.length > 0) {
            // Equal-rate capture needs neither bin boundaries nor averaging.
            for (var j = 0; j < outputLength; j++) {
                var value = this.block[j];
                squareSum += value * value;
                var clipped = Math.max(-1, Math.min(1, value));
                pcm[j] = clipped < 0 ? clipped * 32768 : clipped * 32767;
            }
        } else if (ratio === 3 && this.block.length >= 3) {
            // Common 48 kHz -> 16 kHz capture: each output averages three
            // consecutive samples. Meter them in the same order as before,
            // including the one or two trailing samples not sent as PCM.
            for (var i = 0; i < outputLength; i++) {
                var a = this.block[sourceOffset++];
                var b = this.block[sourceOffset++];
                var c = this.block[sourceOffset++];
                squareSum += a * a;
                squareSum += b * b;
                squareSum += c * c;
                var average = Math.max(-1, Math.min(1, (a + b + c) / 3));
                pcm[i] = average < 0 ? average * 32768 : average * 32767;
            }
            while (sourceOffset < this.block.length) {
                var tail = this.block[sourceOffset++];
                squareSum += tail * tail;
            }
        } else {
            for (var sampleIndex = 0;
                    sampleIndex < this.block.length;
                    sampleIndex++) {
                var meterSample = this.block[sampleIndex];
                squareSum += meterSample * meterSample;
            }

            for (var index = 0; index < outputLength; index++) {
                var next = Math.min(
                    this.block.length,
                    Math.floor((index + 1) * ratio));
                var sum = 0;
                var count = 0;
                while (sourceOffset < next) {
                    sum += this.block[sourceOffset++];
                    count++;
                }
                var sample = count ? sum / count : 0;
                sample = Math.max(-1, Math.min(1, sample));
                pcm[index] =
                    sample < 0 ? sample * 32768 : sample * 32767;
            }
        }

        this.port.postMessage({
            type: 'pcm',
            pcm: pcm.buffer,
            rms: Math.sqrt(squareSum / this.block.length),
            contextTime: currentTime
        }, [pcm.buffer]);
    }
}

registerProcessor(
    'remote-voice-capture',
    RemoteVoiceCaptureProcessor);

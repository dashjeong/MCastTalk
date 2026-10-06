// Keep fractional phase across render quanta: 44.1/48 kHz input becomes 16 kHz.
// Weighted sample-area averaging filters the source before decimation.
class PCMWorklet extends AudioWorkletProcessor {
    constructor() {
        super();
        this.ratio = sampleRate / 16000;
        this.phase = 0;
        this.integral = 0;
        this.packet = new Int16Array(320);
        this.used = 0;
        this.energy = 0;
        this.accepting = true;
        this.port.onmessage = ({ data }) => {
            if (data?.type !== 'flush') return;
            this.accepting = false;
            this.emitPacket();
            this.port.postMessage({ type: 'flushed', id: data.id });
        };
    }
    emitPacket() {
        if (!this.used) return;
        const samples = this.used === 320 ? this.packet : this.packet.slice(0, this.used);
        this.port.postMessage({ type: 'pcm', samples, rms: Math.sqrt(this.energy / this.used) }, [samples.buffer]);
        this.packet = new Int16Array(320);
        this.used = 0;
        this.energy = 0;
    }
    emitSample(value) {
        const clamped = Math.max(-1, Math.min(1, value));
        this.packet[this.used++] = Math.round(clamped * (clamped < 0 ? 32768 : 32767));
        this.energy += clamped * clamped;
        if (this.used === 320) this.emitPacket();
    }
    process(inputs) {
        const channels = inputs[0];
        if (!this.accepting || !channels?.length) return true;
        for (let i = 0; i < channels[0].length; i++) {
            let value = 0;
            for (const channel of channels) value += channel[i] || 0;
            value /= channels.length;
            let remaining = 1;
            while (remaining > 1e-9) {
                const take = Math.min(remaining, this.ratio - this.phase);
                this.integral += value * take;
                this.phase += take;
                remaining -= take;
                if (this.phase >= this.ratio - 1e-9) {
                    this.emitSample(this.integral / this.ratio);
                    this.phase = 0;
                    this.integral = 0;
                }
            }
        }
        // Leave outputs silent; microphone audio is never monitored to speakers.
        return true;
    }
}
registerProcessor('pcm-worklet', PCMWorklet);

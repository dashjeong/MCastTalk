class PCMWorklet extends AudioWorkletProcessor {
    process(inputs, outputs, parameters) {
        const input = inputs[0];
        if (input.length > 0) {
            const channel = input[0];
            const pcm16 = new Int16Array(channel.length);
            for (let i = 0; i < channel.length; i++) {
                let s = Math.max(-1, Math.min(1, channel[i]));
                pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
            }
            this.port.postMessage(pcm16);
        }
        return true;
    }
}
registerProcessor('pcm-worklet', PCMWorklet);

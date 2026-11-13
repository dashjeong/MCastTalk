package app.guidecast.core.audio.nemotron

/** A terminal cancellation cannot be overwritten by a native call that returns late. */
class ProbeRunControl {
    @Volatile var phase: String = "STARTING"
        private set
    @Synchronized fun checkpoint() {
        if (phase == "CANCELLED" || Thread.currentThread().isInterrupted)
            throw InterruptedException("Probe cancelled; no additional inference will start")
    }
    @Synchronized fun cancel() { phase = "CANCELLED" }
    @Synchronized fun update(next: String, write: (String) -> Unit) {
        if (phase != "CANCELLED") phase = next
        write(phase)
    }
}

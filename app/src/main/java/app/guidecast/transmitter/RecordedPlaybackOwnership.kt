package app.guidecast.transmitter

/** Input admission synchronously stops speaker playback before accepting microphone frames. */
internal class RecordedPlaybackOwnership {
    private var inputActive = false
    private var current: Lease? = null
    @Synchronized fun beginPlayback(stop: () -> Unit): Lease {
        check(!inputActive) { "Pause input before playback" }
        current?.close()
        return Lease(stop).also { current = it }
    }
    @Synchronized fun beginInput() { inputActive = true; current?.close(); current = null }
    @Synchronized fun pauseInput() { inputActive = false }
    inner class Lease internal constructor(private val stop: () -> Unit) : java.io.Closeable {
        private var active = true
        fun <T> whileActive(action: () -> T): T = synchronized(this@RecordedPlaybackOwnership) {
            check(active && !inputActive) { "Playback superseded by input" }; action()
        }
        override fun close() = synchronized(this@RecordedPlaybackOwnership) {
            if (active) { active = false; if (current === this) current = null; stop() }
        }
        fun release() = synchronized(this@RecordedPlaybackOwnership) {
            active = false; if (current === this) current = null
        }
    }
}

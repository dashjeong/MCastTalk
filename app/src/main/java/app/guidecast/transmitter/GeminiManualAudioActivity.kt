package app.guidecast.transmitter

import app.guidecast.core.audio.pcmS16LeSignalStats

/**
 * RMS over the packet opens a manual activity; an isolated peak is not enough.
 * This is an energy hint, not a speech recognizer. The pump retains pre-roll and
 * every byte inside an activity, including quieter phonemes and short EOF tails.
 */
internal fun hasManualAudioActivity(pcm: ByteArray): Boolean = pcm.pcmS16LeSignalStats().rms >= 0.002f

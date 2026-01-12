package id.yumtaufikhidayat.jetcalllab.model

import id.yumtaufikhidayat.jetcalllab.enum.TempoPhase

data class CallTempo(
    val phase: TempoPhase,
    val elapsedSeconds: Long,
    val remainingSeconds: Long,
    val timeoutSeconds: Long,
)
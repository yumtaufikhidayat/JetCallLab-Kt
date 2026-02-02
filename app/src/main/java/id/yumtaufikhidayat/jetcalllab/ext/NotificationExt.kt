package id.yumtaufikhidayat.jetcalllab.ext

import id.yumtaufikhidayat.jetcalllab.model.CallTempo
import id.yumtaufikhidayat.jetcalllab.state.CallState

fun CallState.notifTitleFor(): String = when (this) {
    is CallState.ConnectedFinal, is CallState.Connected -> "In call"
    is CallState.Failed, is CallState.FailedFinal -> "Call failed"
    is CallState.Reconnecting -> "Reconnecting..."
    is CallState.Ending -> "Ending call"
    else -> "Call"
}

fun notifTextFor(state: CallState, elapsedSec: Long, tempo: CallTempo?): String =
    when (state) {
        is CallState.Idle -> "Idle"
        is CallState.Preparing -> "Preparing…"

        is CallState.CreatingOffer -> "Creating offer…"
        is CallState.WaitingOffer -> "Waiting offer…"
        is CallState.CreatingAnswer -> "Creating answer…"
        is CallState.WaitingAnswer -> {
            val time = tempo?.remainingSeconds
            if (time != null) "Waiting answer… ${time}s" else "Waiting answer…"
        }
        is CallState.ExchangingIce -> {
            val time = tempo?.remainingSeconds
            if (time != null) "Exchanging ICE… ${time}s" else "Exchanging ICE…"
        }

        is CallState.Ready -> "Ready"

        is CallState.ConnectedFinal -> "Connected (${state.via}) • ${elapsedSec.formatHms()}"
        is CallState.Connected -> "Connected (${state.via})"

        is CallState.Reconnecting -> {
            val time = tempo?.remainingSeconds
            val suffix = if (time != null) " • ${time}s" else ""
            "Attempt ${state.attempt}$suffix"
        }

        is CallState.Ending -> "Ending…"
        is CallState.Failed -> "Failed: ${state.reason}"
        is CallState.FailedFinal -> "Failed: ${state.reason}"

        else -> state::class.simpleName ?: "Call"
    }
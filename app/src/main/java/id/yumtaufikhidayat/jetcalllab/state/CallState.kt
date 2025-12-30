package id.yumtaufikhidayat.jetcalllab.state

sealed class CallState {
    data object Idle : CallState()
    data object RequestingPermission : CallState()
    data object Preparing : CallState()

    // signaling stages
    data object CreatingOffer : CallState()
    data object WaitingOffer : CallState()
    data object CreatingAnswer : CallState()
    data object WaitingAnswer : CallState()
    data object ExchangingIce : CallState()

    data object Ready : CallState()
    data object Connected : CallState()
    data object Ending : CallState()
    data class Failed(val reason: String) : CallState()

    data class ConnectedFinal(val via: String) : CallState() // via: "ICE" or "PC"
    data class FailedFinal(val reason: String) : CallState()
}
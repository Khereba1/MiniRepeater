package com.khereba1.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ServiceStatus { STOPPED, STARTING, RUNNING }

data class RepeaterUiState(
    val status: ServiceStatus = ServiceStatus.STOPPED,
    val iface: String = "—",
    val upstream: String = "wlan0",
    val ssid: String = "",
    val passphrase: String = "",
    val clients: Int = 0,
    val logs: List<String> = emptyList()
)

object RepeaterState {
    private val _state = MutableStateFlow(RepeaterUiState())
    val state: StateFlow<RepeaterUiState> = _state.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun update(transform: (RepeaterUiState) -> RepeaterUiState) {
        _state.update(transform)
    }

    fun appendLog(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        _state.update { current ->
            current.copy(logs = (current.logs + line).takeLast(20))
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }
}

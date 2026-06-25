package com.fraykiee.droidboot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fraykiee.droidboot.model.BootImage
import com.fraykiee.droidboot.model.FirmwareMode
import com.fraykiee.droidboot.usb.GadgetService
import com.fraykiee.droidboot.usb.UsbGadgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val caps: UsbGadgetManager.CapabilityReport? = null,
    val selected: BootImage? = null,
    val mode: FirmwareMode = FirmwareMode.UEFI_CDROM,
    val active: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val log: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val gadget = UsbGadgetManager()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun refreshCapabilities() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true)
        val caps = withContext(Dispatchers.IO) { gadget.checkCapabilities() }
        _ui.value = _ui.value.copy(busy = false, caps = caps)
    }

    fun selectImage(image: BootImage) {
        _ui.value = _ui.value.copy(
            selected = image.copy(mode = _ui.value.mode),
            message = null,
        )
    }

    fun setMode(mode: FirmwareMode) {
        _ui.value = _ui.value.copy(
            mode = mode,
            selected = _ui.value.selected?.copy(mode = mode),
        )
    }

    fun toggle() {
        if (_ui.value.active) stop() else start()
    }

    private fun start() = viewModelScope.launch {
        val img = _ui.value.selected ?: run {
            _ui.value = _ui.value.copy(message = "Сначала выбери образ")
            return@launch
        }
        _ui.value = _ui.value.copy(busy = true, message = null)
        val state = withContext(Dispatchers.IO) { gadget.start(img.copy(mode = _ui.value.mode)) }
        when (state) {
            is UsbGadgetManager.State.Active -> {
                GadgetService.start(getApplication(), img.displayName)
                _ui.value = _ui.value.copy(
                    busy = false, active = true,
                    message = "Подключено как флешка (UDC: ${state.udc})",
                )
            }
            is UsbGadgetManager.State.Error -> _ui.value = _ui.value.copy(
                busy = false, active = false, message = "Ошибка: ${state.message}", log = state.log,
            )
            UsbGadgetManager.State.Stopped -> _ui.value = _ui.value.copy(busy = false, active = false)
        }
    }

    private fun stop() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true)
        withContext(Dispatchers.IO) { gadget.stop() }
        GadgetService.stop(getApplication())
        _ui.value = _ui.value.copy(busy = false, active = false, message = "Отключено")
    }

    /** Принудительный сброс гаджета — безопасно даже после неудачного запуска. */
    fun reset() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true)
        withContext(Dispatchers.IO) { gadget.stop() }
        GadgetService.stop(getApplication())
        _ui.value = _ui.value.copy(busy = false, active = false, message = "Гаджет сброшен, USB восстановлен")
    }

    fun runDiagnostics() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true)
        val dump = withContext(Dispatchers.IO) { gadget.dumpDiagnostics() }
        _ui.value = _ui.value.copy(busy = false, message = "Диагностика USB", log = dump)
    }
}

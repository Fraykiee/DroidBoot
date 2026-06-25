package com.fraykiee.droidboot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fraykiee.droidboot.model.FirmwareMode
import com.fraykiee.droidboot.ui.DroidBootTheme
import com.fraykiee.droidboot.usb.PathResolver

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.refreshCapabilities()
        setContent {
            DroidBootTheme {
                Surface(Modifier.fillMaxSize()) { Screen(vm) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var manualPath by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val img = PathResolver.resolve(ctx, uri)
            if (img != null) vm.selectImage(img)
            else manualPath = uri.toString()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("DroidBoot") }) }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CapabilityCard(ui.caps, onRefresh = vm::refreshCapabilities)

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Образ", style = MaterialTheme.typography.titleMedium)
                    val sel = ui.selected
                    Text(
                        sel?.let { "${it.displayName}\n${it.path}" } ?: "Образ не выбран",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (sel != null) FontFamily.Monospace else FontFamily.Default,
                    )
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !ui.active,
                    ) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp))
                        Text("Выбрать ISO / IMG")
                    }
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("…или путь вручную (/sdcard/Download/x.iso)") },
                        singleLine = true,
                        enabled = !ui.active,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default,
                        trailingIcon = {
                            if (manualPath.isNotBlank()) {
                                IconButton(onClick = {
                                    vm.selectImage(PathResolver.fromPath(manualPath.trim()))
                                }) { Icon(Icons.Default.Check, "Применить путь") }
                            }
                        },
                    )
                }
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("2. Режим прошивки", style = MaterialTheme.typography.titleMedium)
                    ModeRow(
                        selected = ui.mode == FirmwareMode.UEFI_CDROM,
                        title = "UEFI (CD-ROM эмуляция)",
                        subtitle = "Совместимо с UEFI и BIOS. Рекомендуется для большинства ISO.",
                        enabled = !ui.active,
                        onClick = { vm.setMode(FirmwareMode.UEFI_CDROM) },
                    )
                    ModeRow(
                        selected = ui.mode == FirmwareMode.BIOS_USB_HDD,
                        title = "BIOS (USB-HDD / removable)",
                        subtitle = "«Сырой» диск-флешка. Для isohybrid ISO и .img образов.",
                        enabled = !ui.active,
                        onClick = { vm.setMode(FirmwareMode.BIOS_USB_HDD) },
                    )
                    ModeRow(
                        selected = ui.mode == FirmwareMode.LEGACY_FIXED_DISK,
                        title = "Старый BIOS (фиксированный диск)",
                        subtitle = "removable=0. Для древних Award/Phoenix: ищи телефон в " +
                            "Hard Disk Boot Priority и подними наверх. Образ — isohybrid ISO / .img.",
                        enabled = !ui.active,
                        onClick = { vm.setMode(FirmwareMode.LEGACY_FIXED_DISK) },
                    )
                }
            }

            val canStart = ui.selected != null && ui.caps?.isUsable == true
            Button(
                onClick = vm::toggle,
                enabled = (canStart || ui.active) && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (ui.active)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(),
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(if (ui.active) Icons.Default.Stop else Icons.Default.Usb, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ui.active) "Отключить флешку" else "Подключить как флешку")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::reset,
                    enabled = !ui.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(6.dp))
                    Text("Сбросить USB")
                }
                OutlinedButton(
                    onClick = vm::runDiagnostics,
                    enabled = !ui.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(6.dp))
                    Text("Диагностика")
                }
            }

            ui.message?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.log.isNotBlank()) {
                ElevatedCard {
                    Text(ui.log, Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    caps: com.fraykiee.droidboot.usb.UsbGadgetManager.CapabilityReport?,
    onRefresh: () -> Unit,
) {
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Готовность устройства", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить") }
            }
            if (caps == null) {
                Text("Проверка…")
            } else {
                CapRow("Root-доступ", caps.hasRoot)
                CapRow("configfs (/config/usb_gadget)", caps.hasConfigfs)
                CapRow("UDC найден: ${caps.availableUdc.joinToString().ifEmpty { "—" }}",
                    caps.availableUdc.isNotEmpty())
                when (caps.controllerSupport) {
                    com.fraykiee.droidboot.usb.UsbGadgetManager.ControllerSupport.SUPPORTED ->
                        CapRow("USB-контроллер: ${caps.controller} (поддерживается)", true)
                    com.fraykiee.droidboot.usb.UsbGadgetManager.ControllerSupport.KNOWN_BROKEN_MUSB ->
                        CapRow("USB-контроллер: ${caps.controller} (MUSB — несовместим)", false)
                    com.fraykiee.droidboot.usb.UsbGadgetManager.ControllerSupport.UNKNOWN ->
                        CapRow("USB-контроллер: ${caps.controller.ifBlank { "—" }} (не проверен)", true)
                }
                if (caps.controllerSupport ==
                    com.fraykiee.droidboot.usb.UsbGadgetManager.ControllerSupport.KNOWN_BROKEN_MUSB) {
                    Text(
                        "Контроллер MUSB (вендорное ядро MediaTek) роняет ядро при " +
                        "энумерации mass_storage хостом — телефон уйдёт в ребут (прелоадер). " +
                        "Это баг ядра, из приложения не чинится. Запуск возможен, но рискован.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!caps.isUsable) {
                    Text(
                        "Устройство не поддерживает USB-гаджет или нет root. " +
                        "Нужны: рут + ядро с CONFIG_USB_CONFIGFS_MASS_STORAGE.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
            null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModeRow(
    selected: Boolean, title: String, subtitle: String,
    enabled: Boolean, onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

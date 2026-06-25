package com.fraykiee.droidboot.usb

import com.fraykiee.droidboot.model.BootImage
import com.fraykiee.droidboot.root.RootShell

/**
 * Управляет USB-гаджетом через configfs (механизм DriveDroid).
 *
 * Идея: на рутованном Android ядро Linux умеет презентовать себя хосту как
 * произвольное USB-устройство через configfs (/config/usb_gadget). Мы создаём
 * собственный гаджет с функцией mass_storage, в lun.0/file подкладываем ISO,
 * и привязываем гаджет к UDC (USB Device Controller). Хост-ПК видит флешку.
 *
 * ВАЖНО про совместимость:
 *  - Путь к configfs и имя UDC на разных устройствах отличаются - мы их определяем.
 *  - У большинства телефонов UDC уже занят штатным гаджетом (ADB/MTP). Его надо
 *    временно отвязать, а при остановке вернуть обратно.
 */
class UsbGadgetManager {

    companion object {
        private const val CONFIGFS = "/config/usb_gadget"
        private const val GADGET_NAME = "g_droidboot"
        private val GADGET get() = "$CONFIGFS/$GADGET_NAME"

        // 0x1d6b = Linux Foundation, 0x0104 = Multifunction Composite Gadget
        private const val ID_VENDOR = "0x1d6b"
        private const val ID_PRODUCT = "0x0104"
    }

    sealed interface State {
        data object Stopped : State
        data class Active(val image: BootImage, val udc: String) : State
        data class Error(val message: String, val log: String = "") : State
    }

    /** Имя UDC, который мы отвязали, чтобы вернуть его при остановке. */
    private var previousGadgetUdc: Pair<String, String>? = null // gadgetPath -> udc

    /** Прежнее значение sys.usb.config (для property-метода), чтобы вернуть как было. */
    private var previousUsbConfig: String? = null

    /** Проверка предусловий: root, configfs, наличие UDC, mass_storage в ядре. */
    fun checkCapabilities(): CapabilityReport {
        val root = RootShell.isRootAvailable()
        val configfs = RootShell.exists(CONFIGFS)
        val udcs = listUdc()
        val massStorage = root && RootShell.exec(
            "[ -d /sys/class/android_usb ] || grep -q mass_storage /proc/config.gz 2>/dev/null; " +
            "ls /config/usb_gadget 2>/dev/null >/dev/null; echo ok"
        ).success
        val controller = prop("sys.usb.controller").ifBlank { udcs.firstOrNull().orEmpty() }
        return CapabilityReport(
            hasRoot = root,
            hasConfigfs = configfs,
            availableUdc = udcs,
            kernelLooksOk = massStorage,
            controller = controller,
            controllerSupport = classifyController(controller),
        )
    }

    /**
     * Класс USB-контроллера определяет, заработает ли mass_storage без краша.
     *  - DWC3/DWC2 (Qualcomm Snapdragon, многие SoC) - энумерация накопителя стабильна.
     *    Проверено живьём на Mi 8 Lite (SDM660, a800000.dwc3): хост видит «Linux
     *    File-Stor Gadget», ядро не падает.
     *  - MUSB (вендорные ядра MediaTek) - энумерация mass_storage роняет ядро в
     *    musb_g_ep0_irq (NULL deref, ep1in dma_mapping_error → ребут в прелоадер).
     *    Баг ядра, из userspace не чинится. Проверено на Helio G100 (musb-hdrc).
     */
    enum class ControllerSupport { SUPPORTED, KNOWN_BROKEN_MUSB, UNKNOWN }

    private fun classifyController(controller: String): ControllerSupport = when {
        controller.contains("dwc3", ignoreCase = true) ||
            controller.contains("dwc2", ignoreCase = true) -> ControllerSupport.SUPPORTED
        controller.contains("musb", ignoreCase = true) -> ControllerSupport.KNOWN_BROKEN_MUSB
        else -> ControllerSupport.UNKNOWN
    }

    data class CapabilityReport(
        val hasRoot: Boolean,
        val hasConfigfs: Boolean,
        val availableUdc: List<String>,
        val kernelLooksOk: Boolean,
        val controller: String = "",
        val controllerSupport: ControllerSupport = ControllerSupport.UNKNOWN,
    ) {
        val isUsable: Boolean get() = hasRoot && hasConfigfs && availableUdc.isNotEmpty()
    }

    private fun listUdc(): List<String> =
        RootShell.exec("ls /sys/class/udc 2>/dev/null").out
            .flatMap { it.split(Regex("\\s+")) }
            .filter { it.isNotBlank() }

    /** Найти текущий привязанный штатный гаджет (его UDC заполнен). */
    private fun findActiveGadget(): Pair<String, String>? {
        val gadgets = RootShell.exec("ls $CONFIGFS 2>/dev/null").out
            .flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }
        for (g in gadgets) {
            if (g == GADGET_NAME) continue
            val udc = RootShell.readFile("$CONFIGFS/$g/UDC")?.trim()
            if (!udc.isNullOrEmpty()) return "$CONFIGFS/$g" to udc
        }
        return null
    }

    /** Какой гаджет в configfs сейчас привязан к указанному UDC (или null). */
    private fun whoHoldsUdc(udc: String): String? {
        val gadgets = RootShell.exec("ls $CONFIGFS 2>/dev/null").out
            .flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }
        for (g in gadgets) {
            val bound = RootShell.readFile("$CONFIGFS/$g/UDC")?.trim()
            if (bound == udc) return "$CONFIGFS/$g"
        }
        return null
    }

    private fun lsNames(dir: String): List<String> =
        RootShell.exec("ls $dir 2>/dev/null").out
            .flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }

    private fun prop(name: String): String =
        RootShell.exec("getprop $name").text.trim()

    /** Снимок состояния USB-стека - чтобы понять, почему гаджет не встаёт. */
    fun dumpDiagnostics(): String = buildString {
        appendLine("sys.usb.config     = ${prop("sys.usb.config")}")
        appendLine("sys.usb.state      = ${prop("sys.usb.state")}")
        appendLine("sys.usb.controller = ${prop("sys.usb.controller")}")
        appendLine("sys.usb.configfs   = ${prop("sys.usb.configfs")}")
        appendLine("udc                = ${listUdc().joinToString()}")
        appendLine()
        appendLine("=== гаджеты configfs ===")
        for (g in lsNames(CONFIGFS)) {
            val base = "$CONFIGFS/$g"
            appendLine("[$g] UDC='${RootShell.readFile("$base/UDC")?.trim().orEmpty()}'")
            appendLine("  functions: ${lsNames("$base/functions").joinToString().ifBlank { "-" }}")
            for (c in lsNames("$base/configs")) {
                appendLine("  config $c -> ${lsNames("$base/configs/$c").filter { it != "strings" && it != "MaxPower" && it != "bmAttributes" }.joinToString().ifBlank { "(пусто)" }}")
            }
        }
        appendLine()
        appendLine("=== поддерживаемые sys.usb.config (из init) ===")
        appendLine(
            RootShell.exec(
                "grep -rhoE 'sys.usb.config=[a-zA-Z0-9_,]+' " +
                "/system/etc/init /vendor/etc/init /odm/etc/init /init*.rc 2>/dev/null " +
                "| sort -u"
            ).text.ifBlank { "(не найдено)" }
        )
        appendLine("=== куда init пишет mass_storage lun ===")
        appendLine(
            RootShell.exec(
                "grep -rhn 'mass_storage' " +
                "/system/etc/init /vendor/etc/init /odm/etc/init /init*.rc 2>/dev/null " +
                "| head -n 15"
            ).text.ifBlank { "(нет упоминаний - mass_storage-конфига в init нет)" }
        )
    }

    /**
     * Поднять гаджет с указанным образом. Возвращает State.Active при успехе.
     */
    fun start(image: BootImage): State {
        if (!RootShell.isRootAvailable()) return State.Error("Нет root-доступа")

        val udcList = listUdc()
        if (udcList.isEmpty()) return State.Error("Не найден UDC (USB Device Controller)")
        val udc = udcList.first()

        // Образ должен существовать и быть читаемым руту.
        if (!RootShell.exists(image.path)) {
            return State.Error("Файл образа не найден: ${image.path}")
        }

        // Современный Android (configfs USB HAL) композится через sys.usb.config, а не
        // ручной привязкой UDC. Если есть системный гаджет - идём property-методом
        // (неразрушающий, проверен на Qualcomm SDM660/dwc3 и MT6789/musb-hdrc).
        // Иначе - legacy «свой гаджет».
        if (prop("sys.usb.configfs") == "1" && findSystemGadget() != null) {
            return startViaProperty(image)
        }

        val log = StringBuilder()
        fun run(vararg cmd: String): Boolean {
            val r = RootShell.exec(*cmd)
            log.append(cmd.joinToString("; ")).append("\n")
            if (r.text.isNotBlank()) log.append("  -> ").append(r.text).append("\n")
            return r.success
        }

        // 1. Отвязать штатный гаджет, запомнив его UDC для восстановления.
        findActiveGadget()?.let { (path, boundUdc) ->
            previousGadgetUdc = path to boundUdc
            run("echo '' > $path/UDC")
        }

        // 2. Снести наш старый гаджет, если остался от прошлого запуска.
        teardownGadgetTree()

        val lun = "$GADGET/functions/mass_storage.0/lun.0"
        val cdrom = if (image.mode.isCdrom) "1" else "0"
        val removable = if (image.mode.removable) "1" else "0"

        val ok = run(
            "mount -t configfs none $CONFIGFS 2>/dev/null; true",
            "mkdir -p $GADGET",
            "echo $ID_VENDOR > $GADGET/idVendor",
            "echo $ID_PRODUCT > $GADGET/idProduct",
            "echo 0x0200 > $GADGET/bcdUSB",
            "mkdir -p $GADGET/strings/0x409",
            "echo 'DroidBoot' > $GADGET/strings/0x409/manufacturer",
            "echo 'DroidBoot USB' > $GADGET/strings/0x409/product",
            "echo 'droidboot0001' > $GADGET/strings/0x409/serialnumber",
            "mkdir -p $GADGET/functions/mass_storage.0",
            "mkdir -p $lun",
            "echo $removable > $lun/removable",
            "echo $cdrom > $lun/cdrom",
            "echo 1 > $lun/ro",
            "echo 0 > $lun/nofua",
            // file пишем последним: это и есть «вставить флешку».
            "echo '${image.path}' > $lun/file",
            "mkdir -p $GADGET/configs/c.1/strings/0x409",
            "echo 'DroidBoot' > $GADGET/configs/c.1/strings/0x409/configuration",
            "echo 250 > $GADGET/configs/c.1/MaxPower",
            "ln -sf $GADGET/functions/mass_storage.0 $GADGET/configs/c.1/ 2>/dev/null; true",
        )

        if (!ok) {
            restorePreviousGadget()
            return State.Error("Не удалось сконфигурировать гаджет", log.toString())
        }

        // 2b. Проверяем, что функция реально влинкована в конфиг - без этого
        //     MUSB-контроллер откажется привязываться (EINVAL «Invalid argument»).
        if (!RootShell.exists("$GADGET/configs/c.1/mass_storage.0")) {
            log.append("ВНИМАНИЕ: symlink функции в конфиг не создан, повторяю явно\n")
            val ln = RootShell.exec(
                "ln -s $GADGET/functions/mass_storage.0 $GADGET/configs/c.1/mass_storage.0"
            )
            if (ln.err.isNotEmpty()) log.append("  ln -> ${ln.err.joinToString()}\n")
        }

        // 3. Привязать к UDC - момент, когда хост видит подключение.
        var bind = RootShell.exec("echo $udc > $GADGET/UDC")
        log.append("echo $udc > UDC -> ${bind.text.ifBlank { "(ok)" }}\n")
        if (!bind.success || bind.err.isNotEmpty()) {
            // Скорее всего UDC занят вендорным USB-HAL (MIUI). Найдём держателя,
            // отвяжем его и повторим.
            val holder = whoHoldsUdc(udc)
            log.append("UDC '$udc' держит гаджет: ${holder ?: "-"}\n")
            if (holder != null && holder != GADGET) {
                previousGadgetUdc = holder to udc
                RootShell.exec("echo '' > $holder/UDC")
            }
            bind = RootShell.exec("echo $udc > $GADGET/UDC")
            log.append("retry echo $udc > UDC -> ${bind.text.ifBlank { "(ok)" }}\n")
        }
        if (!bind.success || bind.err.isNotEmpty()) {
            val why = bind.err.joinToString("; ").ifBlank { "write error" }
            restorePreviousGadget()
            return State.Error(
                "Не удалось привязать UDC '$udc': $why",
                log.toString() + "\n--- ДИАГНОСТИКА ---\n" + dumpDiagnostics(),
            )
        }

        // Проверим, что привязка РЕАЛЬНО состоялась: на sys.usb.config-устройствах
        // (MUSB/MTK) запись в UDC «успешна», но контроллер не отдаётся - UDC пуст.
        val udcBack = RootShell.readFile("$GADGET/UDC")?.trim()
        if (udcBack.isNullOrEmpty() || udcBack != udc) {
            log.append("UDC после привязки = '${udcBack.orEmpty()}' (не закрепился) → фолбэк на property-метод\n")
            teardownGadgetTree()
            return startViaProperty(image, log)
        }

        val bound = RootShell.readFile("$lun/file")?.trim()
        if (bound.isNullOrEmpty()) {
            stop()
            return State.Error("LUN не принял образ (file пуст)", log.toString())
        }

        return State.Active(image, udc)
    }

    /** Системный гаджет, которым рулит init (обычно g1). */
    private fun findSystemGadget(): String? {
        val gadgets = lsNames(CONFIGFS).filter { it != GADGET_NAME }
        gadgets.firstOrNull { it == "g1" }?.let { return "$CONFIGFS/$it" }
        gadgets.firstOrNull { !RootShell.readFile("$CONFIGFS/$it/UDC")?.trim().isNullOrEmpty() }
            ?.let { return "$CONFIGFS/$it" }
        return gadgets.firstOrNull()?.let { "$CONFIGFS/$it" }
    }

    /** Имя предсозданной функции mass_storage внутри системного гаджета
     *  (на MT6789 это mass_storage.usb0, на других - mass_storage.0). */
    private fun findMassStorageFunc(sys: String): String? =
        lsNames("$sys/functions").firstOrNull { it.startsWith("mass_storage") }

    /**
     * Property-метод для устройств, где USB композится через sys.usb.config (MTK/MUSB,
     * многие Xiaomi). Подкладываем ISO в mass_storage СИСТЕМНОГО гаджета и переключаем
     * sys.usb.config - init сам перепривязывает контроллер.
     */
    fun startViaProperty(image: BootImage, prevLog: StringBuilder = StringBuilder()): State {
        val log = prevLog
        val controller = prop("sys.usb.controller").ifBlank { listUdc().firstOrNull().orEmpty() }
        if (controller.isBlank()) return State.Error("Нет sys.usb.controller / UDC", log.toString())

        val sys = findSystemGadget()
            ?: return State.Error(
                "Не найден системный configfs-гаджет (g1). " +
                "Нужны init-триггеры sys.usb.config - смотри Диагностику.",
                log.toString(),
            )
        // Используем предсозданную инитом функцию mass_storage (mass_storage.usb0 на
        // MT6789), иначе создаём свою - её init залинкует по триггеру sys.usb.config.
        val msFunc = findMassStorageFunc(sys) ?: "mass_storage.0".also {
            RootShell.exec("mkdir -p $sys/functions/$it/lun.0")
        }
        log.append("property-метод: гаджет=$sys, функция=$msFunc, controller=$controller\n")

        val lun = "$sys/functions/$msFunc/lun.0"
        val cdrom = if (image.mode.isCdrom) "1" else "0"
        val removable = if (image.mode.removable) "1" else "0"

        // Настраиваем lun и подкладываем образ ДО переключения композиции -
        // init по триггеру делает только symlink функции, файл не трогает.
        RootShell.exec(
            "echo $removable > $lun/removable",
            "echo $cdrom > $lun/cdrom",
            "echo 1 > $lun/ro",
            "echo '${image.path}' > $lun/file",
        )
        val fileOk = RootShell.readFile("$lun/file")?.trim()
        log.append("lun file = '${fileOk.orEmpty()}'\n")
        if (fileOk.isNullOrEmpty()) {
            return State.Error("Системный гаджет не принял образ в mass_storage", log.toString())
        }

        // Переключаем композицию. ВАЖНО: sys.usb.config после setprop всегда читается
        // как заданное значение - поэтому судим по sys.usb.state, которое инит
        // выставляет, только если реально отработал USB-триггер.
        previousUsbConfig = prop("sys.usb.config").ifBlank { "adb" }

        // MTK init поднимает ЧИСТУЮ композицию "ums" (одна функция mass_storage, без
        // ffs.adb и без acm) только когда vendor.usb.acm_enable=0 И acm_cnt=0 - правило
        // init матчится на точное "=0", а по умолчанию эти пропы пустые, и тогда ни одна
        // ветка mass_storage не срабатывает (UDC не привязывается). Выставляем явно.
        RootShell.exec(
            "setprop vendor.usb.acm_enable 0",
            "setprop vendor.usb.acm_cnt 0",
        )

        fun trySwitch(config: String): Boolean {
            val r = RootShell.exec("setprop sys.usb.config none; sleep 1; setprop sys.usb.config $config; sleep 2")
            val state = prop("sys.usb.state")
            log.append("config=$config → sys.usb.state=$state ${r.err.joinToString().let { if (it.isBlank()) "" else "err=$it" }}\n")
            return state.contains("mass_storage")
        }

        // Голый mass_storage ("ums") - одна функция, без endpoint'а ffs.adb. На MUSB-
        // контроллере MTK композиция mass_storage+adb при энумерации хостом роняет ядро
        // в musb_g_ep0_irq (NULL deref, ep1in dma_mapping_error → ребут в прелоадер),
        // поэтому adb-комбо допускаем как фолбэк ТОЛЬКО на не-MUSB контроллерах.
        val isMusb = controller.contains("musb", ignoreCase = true)
        val candidates = if (isMusb) listOf("mass_storage") else listOf("mass_storage", "mass_storage,adb")
        val success = candidates.any { trySwitch(it) }
        if (!success) {
            RootShell.exec("setprop sys.usb.config none; sleep 1; setprop sys.usb.config $previousUsbConfig")
            previousUsbConfig = null
            return State.Error(
                "init не перешёл в mass_storage (sys.usb.state не изменился). " +
                "Похоже, в твоей прошивке нет USB-триггера mass_storage - нужен другой токен, " +
                "смотри блок «поддерживаемые sys.usb.config» в Диагностике.",
                log.toString(),
            )
        }
        log.append("Успех: телефон презентует образ через системный гаджет.\n")
        return State.Active(image, controller)
    }

    /**
     * Опустить наш гаджет и вернуть штатный USB. Безопасно вызывать в любой момент,
     * в т.ч. когда start() упал на полпути - поэтому это же и «кнопка сброса».
     */
    fun stop(): State {
        RootShell.exec("echo '' > $GADGET/UDC 2>/dev/null; true")
        teardownGadgetTree()
        restorePreviousGadget()
        // Если поднимались через property-метод - убрать образ из системного гаджета
        // и вернуть прежнюю композицию USB.
        findSystemGadget()?.let { sys ->
            findMassStorageFunc(sys)?.let { ms ->
                RootShell.exec("echo '' > $sys/functions/$ms/lun.0/file 2>/dev/null; true")
            }
        }
        val restore = previousUsbConfig ?: "adb"
        RootShell.exec(
            "setprop sys.usb.config none 2>/dev/null; true",
            "setprop sys.usb.config $restore 2>/dev/null; true",
        )
        previousUsbConfig = null
        return State.Stopped
    }

    /** Горячая смена образа без переподключения (как «извлечь и вставить»). */
    fun swapImage(image: BootImage): State {
        val lun = "$GADGET/functions/mass_storage.0/lun.0"
        if (!RootShell.exists(lun)) return start(image)
        // Сначала «извлекаем», затем монтируем новый файл с нужными флагами.
        RootShell.exec(
            "echo '' > $lun/file",
            "echo ${if (image.mode.isCdrom) "1" else "0"} > $lun/cdrom",
            "echo ${if (image.mode.removable) "1" else "0"} > $lun/removable",
            "echo '${image.path}' > $lun/file",
        )
        val udc = listUdc().firstOrNull() ?: return State.Error("Нет UDC")
        return State.Active(image, udc)
    }

    private fun teardownGadgetTree() {
        if (!RootShell.exists(GADGET)) return
        RootShell.exec(
            "echo '' > $GADGET/UDC 2>/dev/null; true",
            "rm -f $GADGET/configs/c.1/mass_storage.0 2>/dev/null; true",
            "rmdir $GADGET/configs/c.1/strings/0x409 2>/dev/null; true",
            "rmdir $GADGET/configs/c.1 2>/dev/null; true",
            "rmdir $GADGET/functions/mass_storage.0 2>/dev/null; true",
            "rmdir $GADGET/strings/0x409 2>/dev/null; true",
            "rmdir $GADGET 2>/dev/null; true",
        )
    }

    private fun restorePreviousGadget() {
        previousGadgetUdc?.let { (path, udc) ->
            RootShell.exec("echo $udc > $path/UDC 2>/dev/null; true")
        }
        previousGadgetUdc = null
    }
}

package com.fraykiee.droidboot.model

/** Как презентовать образ хост-компьютеру. */
enum class FirmwareMode(
    /** Эмулировать CD-ROM (El Torito) вместо обычного диска. */
    val isCdrom: Boolean,
    /** Флаг removable у LUN: true → «флешка», false → «фиксированный жёсткий диск». */
    val removable: Boolean,
) {
    /** Эмуляция CD-ROM (El Torito). Самый совместимый вариант: грузится и в UEFI, и в BIOS. */
    UEFI_CDROM(isCdrom = true, removable = true),

    /** Removable-диск без CD-эмуляции. Для isohybrid ISO и .img - ведёт себя как обычная флешка. */
    BIOS_USB_HDD(isCdrom = false, removable = true),

    /**
     * «Сырой» диск с removable=0 (фиксированный HDD). Старые Award/Phoenix BIOS видят
     * USB-носитель только как жёсткий диск и грузятся с него из Hard Disk Boot Priority,
     * а removable-устройство в этот список не попадает. Образ - isohybrid ISO или .img.
     */
    LEGACY_FIXED_DISK(isCdrom = false, removable = false);
}

/** Выбранный пользователем загрузочный образ. */
data class BootImage(
    /** Абсолютный путь в ФС, видимый руту (например /sdcard/Download/ubuntu.iso). */
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val mode: FirmwareMode = FirmwareMode.UEFI_CDROM,
)

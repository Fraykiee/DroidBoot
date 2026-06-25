package com.fraykiee.droidboot.usb

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.fraykiee.droidboot.model.BootImage
import com.fraykiee.droidboot.model.FirmwareMode

/**
 * Превращает выбранный через SAF content:// Uri в реальный путь ФС, который
 * увидит root-шелл. Покрывает типовые провайдеры (внешнее хранилище, Downloads).
 * Если путь распознать не удалось — возвращаем null, и UI просит ввести путь вручную.
 */
object PathResolver {

    fun resolve(ctx: Context, uri: Uri): BootImage? {
        val name = queryName(ctx, uri) ?: "image.iso"
        val size = querySize(ctx, uri)
        val path = realPath(uri) ?: return null
        return BootImage(
            path = path,
            displayName = name,
            sizeBytes = size,
            mode = if (name.endsWith(".iso", true)) FirmwareMode.UEFI_CDROM
                   else FirmwareMode.BIOS_USB_HDD,
        )
    }

    /** Сборка BootImage из пути, введённого пользователем вручную. */
    fun fromPath(path: String): BootImage {
        val name = path.substringAfterLast('/')
        return BootImage(
            path = path,
            displayName = name,
            sizeBytes = -1,
            mode = if (name.endsWith(".iso", true)) FirmwareMode.UEFI_CDROM
                   else FirmwareMode.BIOS_USB_HDD,
        )
    }

    private fun realPath(uri: Uri): String? {
        // content://com.android.externalstorage.documents/document/primary:Download/x.iso
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = uri.lastPathSegment ?: return null
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", true)) {
                return "/storage/emulated/0/${parts[1]}"
            }
            if (parts.size == 2) {
                // SD-карта / USB-OTG том: /storage/<volume>/<rel>
                return "/storage/${parts[0]}/${parts[1]}"
            }
        }
        // Прямые file:// Uri.
        if (uri.scheme == "file") return uri.path
        return null
    }

    private fun queryName(ctx: Context, uri: Uri): String? {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(ctx: Context, uri: Uri): Long {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) return c.getLong(idx)
        }
        return -1
    }
}

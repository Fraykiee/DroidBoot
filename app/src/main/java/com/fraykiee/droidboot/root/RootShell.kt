package com.fraykiee.droidboot.root

import com.topjohnwu.superuser.Shell

/**
 * Тонкая обёртка над libsu. Все команды гаджета выполняются в одном долгоживущем
 * root-шелле, чтобы состояние (смонтированный configfs и т.п.) сохранялось.
 */
object RootShell {

    init {
        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20)
        )
    }

    /** Есть ли вообще root-доступ. */
    fun isRootAvailable(): Boolean = Shell.getShell().isRoot

    data class Result(val success: Boolean, val out: List<String>, val err: List<String>) {
        val text: String get() = (out + err).joinToString("\n")
    }

    /** Выполнить одну или несколько команд как root. */
    fun exec(vararg commands: String): Result {
        val r = Shell.cmd(*commands).exec()
        return Result(r.isSuccess, r.out, r.err)
    }

    /** Прочитать содержимое файла в sysfs/configfs (или вернуть null). */
    fun readFile(path: String): String? {
        val r = Shell.cmd("cat '$path' 2>/dev/null").exec()
        return if (r.isSuccess) r.out.joinToString("\n") else null
    }

    /** Существует ли путь. */
    fun exists(path: String): Boolean =
        Shell.cmd("[ -e '$path' ]").exec().isSuccess
}

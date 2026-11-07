package com.pickle.patcher.ui.screens

import java.util.Locale

private val storeFileExtensions = setOf(
    "7z", "amxx", "apk", "avi", "bin", "bmp", "bz2", "cfg", "cs", "dat", "dem",
    "gz", "gif", "inc", "ini", "jpeg", "jpg", "json", "log", "lua", "md", "mkv",
    "mp3", "mp4", "ogg", "pdf", "png", "rar", "sav", "sma", "spr", "tar", "tga",
    "txt", "wad", "wav", "webp", "xz", "zip",
)

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }

    val decimals = when {
        value >= 100.0 -> 0
        value >= 10.0 -> 1
        else -> 2
    }
    return String.format(Locale.US, "%.${decimals}f %s", value, units[unit])
}

internal fun cleanStoreFileName(name: String): String {
    val trimmed = name.trim()
    if (!trimmed.endsWith(".so", ignoreCase = true)) return trimmed

    val base = trimmed.dropLast(3)
    if (base.endsWith(".so", ignoreCase = true)) return base

    val extension = base.substringAfterLast('.', "").lowercase(Locale.US)
    return if (extension in storeFileExtensions) base else trimmed
}

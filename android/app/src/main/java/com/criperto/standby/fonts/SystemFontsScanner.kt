package com.criperto.standby.fonts

import java.io.File

data class SystemFontEntry(
    val familyName: String,
    val fileName: String,
    val absolutePath: String,
)

object SystemFontsScanner {
    private val defaultDirs = listOf(
        "/system/fonts",
        "/product/fonts",
        "/vendor/fonts",
    )

    private val weightSuffixRegex = Regex(
        "(?i)[-_](thin|extralight|ultralight|light|regular|normal|book|medium|semibold|demibold|bold|extrabold|ultrabold|black|heavy|italic|oblique|variable|vf)+$"
    )

    fun normalizeFamilyName(rawFileName: String): String {
        val base = rawFileName.substringBeforeLast('.')
        val stripped = weightSuffixRegex.replace(base, "")
            .replace(weightSuffixRegex, "")
            .ifBlank { base }
        return stripped
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
    }

    fun scanDeviceFonts(dirs: List<String> = defaultDirs): List<SystemFontEntry> {
        val byFamily = linkedMapOf<String, SystemFontEntry>()

        // Built-in Android generic families always available
        listOf(
            "sans-serif" to "Sans Serif (Android)",
            "sans-serif-medium" to "Sans Serif Medium",
            "sans-serif-condensed" to "Sans Serif Condensed",
            "serif" to "Serif",
            "monospace" to "Monospace",
            "cursive" to "Cursive",
        ).forEach { (fam, label) ->
            byFamily[label] = SystemFontEntry(
                familyName = fam,
                fileName = "",
                absolutePath = "",
            )
        }

        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            val files = dir.listFiles { f ->
                f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
            }?.sortedBy { it.name } ?: continue

            for (file in files) {
                val n = file.name
                // Skip pure emoji/symbol fallback tables to keep picker focused on readable text/clock fonts
                if (n.contains("Emoji", ignoreCase = true) || n.startsWith("NotoSans", ignoreCase = true) && n.length > 22) {
                    continue
                }
                val family = normalizeFamilyName(n)
                if (family.isBlank()) continue
                val preferRegular = n.contains("Regular", ignoreCase = true) || !n.contains("Italic", ignoreCase = true)
                if (!byFamily.containsKey(family) || preferRegular) {
                    byFamily[family] = SystemFontEntry(
                        familyName = family,
                        fileName = file.name,
                        absolutePath = file.absolutePath,
                    )
                }
            }
        }
        return byFamily.values.toList()
    }
}

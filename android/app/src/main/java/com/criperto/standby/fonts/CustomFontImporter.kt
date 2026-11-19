package com.criperto.standby.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CustomFontImporter {
    fun importedDir(context: Context): File =
        File(context.filesDir, "fonts/imported").apply { mkdirs() }

    fun listImportedFonts(context: Context): List<File> {
        return importedDir(context)
            .listFiles { f ->
                f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    suspend fun importFontFromUri(
        context: Context,
        uri: Uri,
        desiredFileName: String? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = desiredFileName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "font_${System.currentTimeMillis()}.ttf"
            val ext = rawName.substringAfterLast('.', "ttf").lowercase().let {
                if (it == "otf" || it == "ttf") it else "ttf"
            }
            val safeBase = rawName.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                .ifBlank { "custom_font" }
            val dst = File(importedDir(context), "$safeBase.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            } ?: error("No se pudo abrir el archivo de fuente")

            // Validate font parses into Android Typeface
            val tf = runCatching { Typeface.createFromFile(dst) }.getOrNull()
            if (tf == null || tf === Typeface.DEFAULT) {
                if (dst.length() < 256L) {
                    dst.delete()
                    error("El archivo no es una fuente TTF/OTF válida")
                }
            }
            dst
        }
    }
}

object SvgAssetManager {
    fun svgsDir(context: Context): File = File(context.filesDir, "svgs").apply { mkdirs() }

    fun listImportedSvgs(context: Context): List<File> {
        return svgsDir(context)
            .listFiles { f -> f.isFile && f.extension.equals("svg", true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    suspend fun importSvgFromUri(
        context: Context,
        uri: Uri,
        desiredName: String? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("No se pudo leer el archivo .SVG")
            // Validate SVG syntax with AndroidSVG
            val parsed = runCatching {
                SVG.getFromInputStream(bytes.inputStream())
            }.getOrNull() ?: error("El archivo no es un .SVG válido")
            check(parsed.documentWidth != 0f || parsed.documentViewBox != null) {
                "SVG sin dimensiones ni viewBox"
            }

            val rawName = desiredName?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "icon_${System.currentTimeMillis()}.svg"
            val safeBase = rawName.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                .ifBlank { "icon_${System.currentTimeMillis()}" }
            val dst = File(svgsDir(context), "$safeBase.svg")
            dst.writeBytes(bytes)
            dst
        }
    }
}

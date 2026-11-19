package com.criperto.standby.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ModelDownloadStatus(
    val activeModelId: String? = null,
    val progressFraction: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val installedIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

class ModelDownloader(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var activeJob: Job? = null

    private val _status = MutableStateFlow(ModelDownloadStatus(installedIds = scanInstalled()))
    val statusFlow: StateFlow<ModelDownloadStatus> = _status.asStateFlow()

    fun refreshInstalled() {
        _status.value = _status.value.copy(installedIds = scanInstalled())
    }

    private fun scanInstalled(): Set<String> {
        return AiModelCatalog.models
            .filter { AiModelCatalog.isInstalled(context, it) }
            .map { it.id }
            .toSet()
    }

    fun startDownload(spec: AiModelSpec, onComplete: (File) -> Unit = {}) {
        if (_status.value.activeModelId != null) return
        val dst = AiModelCatalog.modelFile(context, spec)
        if (AiModelCatalog.isInstalled(context, spec)) {
            refreshInstalled()
            onComplete(dst)
            return
        }

        val part = File(AiModelCatalog.modelsDir(context), "${spec.fileName}.part")
        activeJob = scope.launch {
            try {
                val existingBytes = if (part.exists()) part.length() else 0L
                val reqBuilder = Request.Builder().url(spec.downloadUrl)
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                _status.value = _status.value.copy(
                    activeModelId = spec.id,
                    progressFraction = 0f,
                    downloadedBytes = existingBytes,
                    totalBytes = spec.approxSizeMb * 1024L * 1024L,
                    errorMessage = null,
                )

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        error("Error HTTP ${resp.code} descargando ${spec.displayName}")
                    }
                    val append = resp.code == 206 && existingBytes > 0L
                    val contentLen = resp.body?.contentLength() ?: -1L
                    val total = if (contentLen > 0) {
                        (if (append) existingBytes else 0L) + contentLen
                    } else {
                        spec.approxSizeMb * 1024L * 1024L
                    }

                    val stream = resp.body?.byteStream() ?: error("Respuesta vacía")
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var written = if (append) existingBytes else 0L
                        while (isActive) {
                            val read = stream.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            written += read
                            val frac = if (total > 0L) {
                                (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else 0.5f
                            _status.value = _status.value.copy(
                                progressFraction = frac,
                                downloadedBytes = written,
                                totalBytes = total,
                            )
                        }
                    }
                }

                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                _status.value = ModelDownloadStatus(
                    activeModelId = null,
                    progressFraction = 1f,
                    installedIds = scanInstalled(),
                )
                onComplete(dst)
            } catch (_: CancellationException) {
                _status.value = _status.value.copy(activeModelId = null)
            } catch (t: Throwable) {
                _status.value = _status.value.copy(
                    activeModelId = null,
                    errorMessage = t.message ?: "Error al descargar modelo ONNX",
                )
            }
        }
    }

    fun cancelActiveDownload() {
        activeJob?.cancel()
        activeJob = null
        _status.value = _status.value.copy(activeModelId = null)
    }

    fun deleteModel(spec: AiModelSpec) {
        if (_status.value.activeModelId == spec.id) {
            cancelActiveDownload()
        }
        AiModelCatalog.modelFile(context, spec).delete()
        File(AiModelCatalog.modelsDir(context), "${spec.fileName}.part").delete()
        refreshInstalled()
    }
}

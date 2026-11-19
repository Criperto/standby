package com.criperto.standby.ai

import android.content.Context
import java.io.File

data class AiModelSpec(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val fileName: String,
    val downloadUrl: String,
    val approxSizeMb: Int,
    val inputSize: Int,
    val isLowRes: Boolean,
    val isSegmentation: Boolean = true,
)

object AiModelCatalog {
    val models: List<AiModelSpec> = listOf(
        AiModelSpec(
            id = "birefnet-general-lite",
            displayName = "BiRefNet General Lite (Recomendado)",
            subtitle = "Máxima calidad de recorte 1024×1024 (igual al lockscreen de escritorio)",
            fileName = "birefnet-general-lite.onnx",
            downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-general-bb_swin_v1_tiny-256.onnx",
            approxSizeMb = 112,
            inputSize = 1024,
            isLowRes = false,
        ),
        AiModelSpec(
            id = "rmbg-1.4-fp16",
            displayName = "RMBG-1.4 (Bordes finos y cabello)",
            subtitle = "Excelente separación de sujeto y fondo complejo 1024×1024",
            fileName = "rmbg-1.4.onnx",
            downloadUrl = "https://huggingface.co/briaai/RMBG-1.4/resolve/main/onnx/model_fp16.onnx",
            approxSizeMb = 88,
            inputSize = 1024,
            isLowRes = false,
        ),
        AiModelSpec(
            id = "isnet-general-use",
            displayName = "IS-Net General",
            subtitle = "Equilibrado para fotografía y paisajes 1024×1024",
            fileName = "isnet-general-use.onnx",
            downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx",
            approxSizeMb = 170,
            inputSize = 1024,
            isLowRes = false,
        ),
        AiModelSpec(
            id = "isnet-anime",
            displayName = "IS-Net Anime / Ilustración",
            subtitle = "Optimizado para arte 2D, personajes y wallpapers ilustrados 1024×1024",
            fileName = "isnet-anime.onnx",
            downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx",
            approxSizeMb = 168,
            inputSize = 1024,
            isLowRes = false,
        ),
        AiModelSpec(
            id = "u2netp",
            displayName = "U²-Netp Ultra-Ligero",
            subtitle = "Descarga mínima (4 MB), inferencia rápida 320×320 con suavizado extra",
            fileName = "u2netp.onnx",
            downloadUrl = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx",
            approxSizeMb = 4,
            inputSize = 320,
            isLowRes = true,
        ),
        AiModelSpec(
            id = "yunet",
            displayName = "YuNet Face Detector (Presencia 24/7)",
            subtitle = "Detección facial ONNX ultraligera (< 1 MB) para cámara 24/7",
            fileName = "face_detection_yunet.onnx",
            downloadUrl = "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
            approxSizeMb = 1,
            inputSize = 320,
            isLowRes = true,
            isSegmentation = false,
        ),
    )

    val segmentationModels: List<AiModelSpec>
        get() = models.filter { it.isSegmentation }

    fun findById(id: String): AiModelSpec {
        return models.firstOrNull { it.id == id } ?: models.first()
    }

    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, spec: AiModelSpec): File {
        return File(modelsDir(context), spec.fileName)
    }

    fun isInstalled(context: Context, spec: AiModelSpec): Boolean {
        val f = modelFile(context, spec)
        return f.exists() && f.length() > 16_384L
    }
}

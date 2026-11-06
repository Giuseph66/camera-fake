package br.com.neurelix.vcam.core.model

import kotlinx.serialization.Serializable

/** Tipo de fonte de imagem. */
@Serializable
enum class SourceKind(val label: String) {
    IMAGE("Fotografia"),
    VIDEO("Vídeo"),
    CAMERA_BACK("Câmera traseira"),
    CAMERA_FRONT("Câmera frontal"),
    PATTERN("Diagnóstico"),
}

@Serializable
enum class PatternType(val label: String) {
    COLOR_BARS("Barras de cores"),
    GRADIENT("Gradiente"),
    NUMBERED("Quadros numerados"),
    NOISY_BARS("Barras com ruído (teste de denoise)"),
}

/**
 * Descrição serializável de uma fonte. [path] aponta para uma cópia privada do app
 * (filesDir/sources) para IMAGE e VIDEO; nunca para o armazenamento compartilhado.
 */
@Serializable
data class SourceSpec(
    val kind: SourceKind,
    val path: String? = null,
    val label: String = kind.label,
    val pattern: PatternType = PatternType.NUMBERED,
    val addedAtMs: Long = 0L,
) {
    val id: String get() = "${kind.name}:${path ?: pattern.name}"
}

@Serializable
enum class FitMode(val label: String) {
    CROP("Preencher (corta)"),
    FIT("Ajustar (barras)"),
    STRETCH("Esticar"),
}

@Serializable
enum class DropPolicy(val label: String) {
    /** Limita ao FPS-alvo; quadros excedentes ou atrasados são descartados (último vence). */
    NEWEST("Manter o mais novo"),
    /** Renderiza todo quadro entregue pela fonte, sem limite de FPS. */
    NONE("Não descartar"),
}

@Serializable
enum class LostSourcePolicy(val label: String) {
    HOLD_LAST("Congelar último quadro"),
    PLACEHOLDER("Mostrar aviso 'fonte perdida'"),
    STOP("Parar saída"),
}

@Serializable
enum class QualityPriority(val label: String) {
    QUALITY("Qualidade"),
    BALANCED("Equilibrado"),
    LATENCY("Baixa latência"),
}

/** Ajustes de saída do motor. Referência inicial: 1280x720 @ 30 FPS. */
@Serializable
data class OutputSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val fit: FitMode = FitMode.CROP,
    val mirrorHorizontal: Boolean = false,
    val dropPolicy: DropPolicy = DropPolicy.NEWEST,
    val lostSourcePolicy: LostSourcePolicy = LostSourcePolicy.PLACEHOLDER,
    val powerSaving: Boolean = false,
    val priority: QualityPriority = QualityPriority.BALANCED,
    val loopVideo: Boolean = true,
    /** Enquadramento: zoom >= 1 e deslocamento normalizado (-1..1). */
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** Rotação extra do conteúdo, múltiplos de 90. */
    val rotationDegrees: Int = 0,
) {
    /** FPS efetivo considerando economia de energia. */
    val effectiveFps: Int get() = if (powerSaving) minOf(fps, 15) else fps
}

/** Parâmetros de processamento. Valores neutros = filtro desligado. Todos são "intensidades". */
@Serializable
data class FilterParams(
    val enabled: Boolean = true,
    val brightness: Float = 0f,      // -1..1
    val contrast: Float = 1f,        // 0..2
    val saturation: Float = 1f,      // 0..2
    val temperature: Float = 0f,     // -1 (frio) .. 1 (quente)
    val gamma: Float = 1f,           // 0.4..2.5
    val sharpness: Float = 0f,       // 0..1
    val denoise: Float = 0f,         // 0..1 (espacial bilateral + temporal)
    val exposureEv: Float = 0f,      // -2..2 ganho digital em EV
    val autoLight: Boolean = false,  // ganho digital automático por luminância média
    val autoLightStrength: Float = 0.7f,
) {
    val isNeutral: Boolean
        get() = !enabled || (brightness == 0f && contrast == 1f && saturation == 1f && temperature == 0f &&
            gamma == 1f && sharpness == 0f && denoise == 0f && exposureEv == 0f && !autoLight)

    fun sanitized() = copy(
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(0f, 2f),
        saturation = saturation.coerceIn(0f, 2f),
        temperature = temperature.coerceIn(-1f, 1f),
        gamma = gamma.coerceIn(0.4f, 2.5f),
        sharpness = sharpness.coerceIn(0f, 1f),
        denoise = denoise.coerceIn(0f, 1f),
        exposureEv = exposureEv.coerceIn(-2f, 2f),
        autoLightStrength = autoLightStrength.coerceIn(0f, 1f),
    )
}

@Serializable
data class FilterProfile(val name: String, val params: FilterParams)

/** Origem de uma sessão/consumidor autorizado. */
@Serializable
data class AuthorizedConsumer(
    val packageName: String,
    val certSha256: String,
    val label: String,
    val approvedAtMs: Long,
)

@Serializable
data class PendingConsumer(val packageName: String, val certSha256: String, val label: String, val requestedAtMs: Long)

/** Histórico de teste/sessão (integração). */
@Serializable
data class SessionRecord(
    val id: Long,
    val consumerPackage: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val width: Int,
    val height: Int,
    val framesDelivered: Long = 0,
    val note: String = "",
)

/** Estado do motor visível à UI. */
enum class EngineState { IDLE, STARTING, RUNNING_LOCAL, RUNNING_EXTERNAL, SOURCE_LOST, ERROR }

data class EngineMetrics(
    val sourceFps: Float = 0f,
    val deliveredFps: Float = 0f,
    val minFps: Float = 0f,
    val maxFps: Float = 0f,
    val fpsStdDev: Float = 0f,
    val dropped: Long = 0,
    val delivered: Long = 0,
    val latencyAvgMs: Float = 0f,
    val latencyP95Ms: Float = 0f,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val sourceFormat: String = "-",
    val outputFormat: String = "-",
    val outputs: Int = 0,
    val avgLuma: Float = -1f,
    val cpuPercent: Float = 0f,
    val memoryMb: Float = 0f,
)

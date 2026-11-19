package com.criperto.standby.core

import android.graphics.Bitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

@Serializable
data class BackgroundConfig(
    val mode: String = "black", // "black" | "wallpaper"
    val wallpaperUri: String? = null,
    val brightness: Double = 0.35,
    val blurRadius: Double = 0.0,
    val nightDimFactor: Double = 0.5,
    val shaderId: String? = null,
    val shaderParams: Map<String, Double> = emptyMap(),
)

@Serializable
data class PresetMeta(
    val motionDefaults: Map<String, String> = emptyMap(),
    val targetAspect: String? = null,
    val createdBy: String? = null,
)

@Serializable
data class GradientStop(
    val pos: Double = 0.0,
    val colorChoice: String = "primary",
    val isCustom: Boolean = false,
    val hex: String = "#A8C7FA",
    val alpha: Double = 1.0,
)

@Serializable
data class GradientSpec(
    val enabled: Boolean = false,
    @SerialName("type") val gradType: String = "linear",
    val p0x: Double = 0.5,
    val p0y: Double = 0.0,
    val p1x: Double = 0.5,
    val p1y: Double = 1.0,
    val p2x: Double = 1.0,
    val p2y: Double = 0.0,
    val stops: List<GradientStop> = listOf(
        GradientStop(pos = 0.0, colorChoice = "primary", hex = "#A8C7FA"),
        GradientStop(pos = 1.0, colorChoice = "tertiary", hex = "#DDBCE0"),
    ),
)

@Serializable
data class HitboxConfig(
    val enabled: Boolean = false,
    val mode: String = "auto",
    val consume: Boolean = true,
    val enabledWhen: String = "",
    val padding: Double = 0.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val width: Double? = null,
    val height: Double? = null,
)

@Serializable
data class ActionsConfig(
    val onTap: String = "",
    val onTapOutside: String = "",
    val onDoubleTap: String = "",
    val onLongPress: String = "",
)

@Serializable
data class SceneObject(
    val id: String,
    @SerialName("type") val objType: String = "clock",
    val name: String = "",
    val alias: String = "",
    val zIndex: Int = 1,
    val visible: Boolean = true,
    val locked: Boolean = false,

    val groupId: String? = null,
    val groupOffsetX: Double = 0.0,
    val groupOffsetY: Double = 0.0,
    val groupWidth: Double = 240.0,
    val groupHeight: Double = 120.0,
    val childrenIds: List<String> = emptyList(),
    val collapsed: Boolean = false,
    val state: Map<String, Double> = emptyMap(),
    val autoLayout: String = "none",
    val autoLayoutGap: Double = 12.0,
    val autoLayoutAlign: String = "center",

    @SerialName("posX_ratio") val posXRatio: Double = 0.5,
    @SerialName("posY_ratio") val posYRatio: Double = 0.5,
    val baseWidth: Double = 300.0,
    val baseHeight: Double = 120.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val uniformScale: Double = 1.0,
    val rotation: Double = 0.0,
    val pivotX: Double = 0.5,
    val pivotY: Double = 0.5,
    val elementOpacity: Double = 1.0,

    val perspectiveX: Double = 0.0,
    val perspectiveY: Double = 0.0,
    val perspectiveDistance: Double = 800.0,
    val parallaxFollow: Boolean = false,
    val parallaxFactor: Double = 1.0,

    val shapeType: String = "rectangle",
    val radiusUniform: Double = 16.0,
    val radiusIndependent: Boolean = false,
    val radiusTopLeft: Double = 16.0,
    val radiusTopRight: Double = 16.0,
    val radiusBottomRight: Double = 16.0,
    val radiusBottomLeft: Double = 16.0,
    val circleStart: Double = 0.0,
    val circleSweep: Double = 360.0,
    val circleRatio: Double = 0.0,
    val polygonSides: Int = 3,
    val polygonRadius: Double = 0.0,
    val starPoints: Int = 5,
    val starRatio: Double = 0.382,
    val starRadius: Double = 0.0,

    val fontChoice: Int = 1,
    val useSystemFont: Boolean = true,
    val customFontFamily: String = "Roboto",
    val fontSourceId: String? = null,
    val fontFileName: String? = null,
    val fontWeight: Int = 400,
    val fontItalic: Boolean = false,
    val baseFontSize: Double = 110.0,
    val letterSpacing: Double = 0.0,
    val textAlign: String = "center",
    val timeFormat: Int = 0,
    val dateFormat: Int = 0,
    val customContent: String = "",

    val textColorChoice: String = "white",
    val isCustomColor: Boolean = false,
    val customHex: String = "#ffffff",
    val fillAlpha: Double = 1.0,
    val strokeEnabled: Boolean = false,
    val strokeWidth: Double = 2.0,
    val strokeColorChoice: String = "black",
    val isCustomStrokeColor: Boolean = false,
    val customStrokeHex: String = "#000000",
    val gradient: GradientSpec? = null,
    val strokeGradient: GradientSpec? = null,
    val tintGradient: GradientSpec? = null,

    val shadowEnabled: Boolean = false,
    val shadowOffsetX: Double = 0.0,
    val shadowOffsetY: Double = 6.0,
    val shadowBlur: Double = 0.75,
    val shadowColorChoice: String = "black",
    val isCustomShadowColor: Boolean = false,
    val customShadowHex: String = "#000000",
    val customShadowAlpha: Double = 0.85,

    val blurEnabled: Boolean = false,
    val blurAmount: Double = 0.85,
    val blurMax: Double = 48.0,
    val glassTintOpacity: Double = 0.6,

    val liquidGlassEnabled: Boolean = false,
    val liquidBezel: Double = 12.0,
    val liquidThickness: Double = 24.0,
    val liquidIor: Double = 1.5,
    val liquidDispersion: Double = 0.06,
    val liquidFrost: Double = 1.5,
    val liquidSaturation: Double = 1.2,
    val liquidRim: Double = 0.3,
    val liquidLightAngle: Double = 315.0,
    val liquidTintAmount: Double = 0.25,
    val shaderId: String? = null,
    val shaderParams: Map<String, Double> = emptyMap(),

    val isMaskGroup: Boolean = false,
    val isMask: Boolean = false,
    val maskTargetId: String? = null,
    val maskTargetIds: List<String> = emptyList(),
    val maskMode: String = "cutout",
    val maskCollapsed: Boolean = false,
    val maskFeather: Double = 0.0,
    val maskModelName: String = "birefnet-general-lite",
    val invertMask: Boolean = false,
    val maskChoke: Double = 1.5,
    val depthCutoutMode: String = "limit_50",
    val depthCutoutThreshold: Double = 50.0,

    val svgSource: String? = null,
    val iconName: String = "",
    val mediaAction: String = "play_pause",
    val hoverPressEffects: Boolean = true,
    val hideWhenNoCover: Boolean = false,
    val hideWhenNoMedia: Boolean = false,
    val wizDeviceId: String? = null,
    val wizAction: String? = null,
    val wizSceneId: Int? = null,
    val wizStyle: String = "pill_slider", // pill_slider | tile | compact | minimal
    val wizTitleTemplate: String = "\$WIZ_NAME",
    val wizSubtitleTemplate: String = "Escritorio",
    val wizShowPercent: Boolean = true,
    val wizShowIcon: Boolean = false,
    val wizUseLampColor: Boolean = false,
    val wizBgColorChoice: String = "surfaceContainer",
    val wizIsCustomBgColor: Boolean = false,
    val wizCustomBgHex: String = "#202924",
    val wizBgAlpha: Double = 0.94,
    val wizBarColorChoice: String = "primaryContainer",
    val wizIsCustomBarColor: Boolean = false,
    val wizCustomBarHex: String = "#3B5249",
    val wizBarAlpha: Double = 0.96,
    val wizAccentColorChoice: String = "primary",
    val wizIsCustomAccentColor: Boolean = false,
    val wizCustomAccentHex: String = "#A8C7FA",

    val visibleCondition: String = "",
    val fadeInMs: Int = 150,
    val fadeOutMs: Int = 150,
    val fadeDelayMs: Int = 0,
    val expressions: Map<String, String> = emptyMap(),
    val motion: Map<String, String> = emptyMap(),
    val hitbox: HitboxConfig? = null,
    val actions: ActionsConfig? = null,
    val activeContentWidth: Double? = null,
    val activeContentHeight: Double? = null,
)

@Serializable
data class StandByPreset(
    val schemaVersion: Int = 1,
    val name: String = "Sin título",
    val description: String = "",
    val author: String = "",
    val background: BackgroundConfig = BackgroundConfig(),
    val state: Map<String, Double> = emptyMap(),
    val meta: PresetMeta = PresetMeta(),
    val objects: List<SceneObject> = emptyList(),
)

@Serializable
data class ValidationReport(
    val valid: Boolean = false,
    val sanitizedJson: String = "",
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val missingFontsourceIds: List<String> = emptyList(),
    val referencedSvgs: List<String> = emptyList(),
    val needsFrameTick: Boolean = false,
    val needsSecondTick: Boolean = false,
)

@Serializable
data class TimeWindowDto(
    val startMinuteOfDay: Int = 0,
    val endMinuteOfDay: Int = 1439,
)

@Serializable
data class DateRangeDto(
    val startYyyymmdd: Long = 20260101,
    val endYyyymmdd: Long = 20261231,
    val isExclusion: Boolean = true,
)

@Serializable
data class TriggerRulesDto(
    val enabled: Boolean = true,
    val chargingReq: String = "any_charging", // any_charging | ac_only | usb_only | wireless_only | always_ignore_charging
    val orientationReq: String = "landscape_only", // landscape_only | any_orientation
    val timeWindows: List<TimeWindowDto> = emptyList(),
    val weekdaysMask: Int = 0x7F,
    val dateRanges: List<DateRangeDto> = emptyList(),
    val nightWindow: TimeWindowDto? = TimeWindowDto(23 * 60, 7 * 60),
    val useSystemBrightness: Boolean = false,
    val standbyBrightness: Float = 0.28f,
    val nightBrightness: Float = 0.06f,
    val hubBrightness: Float = 0.58f,
    val presenceMode: String = "disabled", // disabled | light_only | face_only | face_or_light | face_and_light
    val darkThresholdLux: Float = 8.0f,
    val luxHysteresis: Float = 3.0f,
    val sleepDelaySec: Int = 20,
    val cameraPollIntervalSec: Int = 5,
    val nightBlackoutEnabled: Boolean = true,
)

@Serializable
data class TriggerSnapshotDto(
    val isCharging: Boolean = false,
    val chargeSource: String = "none", // none | ac | usb | wireless
    val isLandscape: Boolean = true,
    val nowEpochMs: Long = 0L,
    val localMinuteOfDay: Int = 720,
    val localWeekdayMon0: Int = 0,
    @SerialName("localYyyymmdd")
    val localYyyyMmDd: Long = 20261118,
    val ambientLux: Float? = null,
    val faceDetected: Boolean? = null,
    val lastPresenceEpochMs: Long = 0L,
    val hubOpen: Boolean = false,
    val manualLaunch: Boolean = false,
    val prevDarkRoom: Boolean = false,
)

@Serializable
data class StandByDecisionDto(
    val shouldBeActive: Boolean = false,
    val displayMode: String = "inactive", // inactive | active | night_active | amoled_blackout
    val shouldPollCamera: Boolean = false,
    val targetBrightness: Float = -1f,
    val extraDimAlpha: Float = 0f,
    val isDarkRoom: Boolean = false,
    val reason: String = "",
)

@Serializable
data class SnapGuideDto(
    val axis: String = "v",
    val pos: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val isEqualSpacing: Boolean = false,
)

@Serializable
data class SpacingLabelDto(
    val axis: String = "h",
    val start: Double = 0.0,
    val length: Double = 0.0,
    val crossPos: Double = 0.0,
)

@Serializable
data class SnapResultDto(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val snappedCenterX: Boolean = false,
    val snappedCenterY: Boolean = false,
    val snappedThirdLeft: Boolean = false,
    val snappedThirdRight: Boolean = false,
    val snappedThirdTop: Boolean = false,
    val snappedThirdBottom: Boolean = false,
    val guides: List<SnapGuideDto> = emptyList(),
    val spacings: List<SpacingLabelDto> = emptyList(),
)

@Serializable
data class WorldTransformDto(
    val anchorX: Double = 0.0,
    val anchorY: Double = 0.0,
    val visualCenterX: Double = 0.0,
    val visualCenterY: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val width: Double = 100.0,
    val height: Double = 100.0,
)

@Serializable
data class ActionSideEffectDto(
    val type: String = "",
    val command: String? = null,
    val elementId: String? = null,
    val eventName: String? = null,
    val delayMs: Long? = null,
    val actionStr: String? = null,
    val targetAlias: String? = null,
    val targetEvent: String? = null,
    val depth: Int? = null,
    val deviceId: String? = null,
    val arg: String? = null,
)

@Serializable
data class ActionOutputDto(
    val stateValues: Map<String, Double> = emptyMap(),
    val stateChanged: Boolean = false,
    val sideEffects: List<ActionSideEffectDto> = emptyList(),
)

@Serializable
data class MaterialYouPaletteDto(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val tertiary: Long,
    val tertiaryContainer: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val surfaceContainer: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
    val background: Long,
    val outline: Long,
    val error: Long,
)

data class MediaSnapshot(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val hasCover: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val progress: Double = 0.0,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val isShuffle: Boolean = false,
    val isLoop: Boolean = false,
    val canNext: Boolean = false,
    val canPrev: Boolean = false,
    val albumArt: Bitmap? = null,
)

data class PrimaryWizStatus(
    val isOn: Boolean = false,
    val dimming: Int = 100,
    val name: String = "WiZ",
    val hex: String = "#ffffff",
)

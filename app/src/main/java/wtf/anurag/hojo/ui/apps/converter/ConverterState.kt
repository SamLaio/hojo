package wtf.anurag.hojo.ui.apps.converter

import java.io.File

/**
 * Mirrors the settings panel of x4converter.rho.sh exactly.
 * Value ranges and defaults match the web converter.
 */
data class ConverterSettings(
    // ── Device ────────────────────────────────────────────────────────────
    /** Rotation in degrees: 0, 90, 180, 270 */
    val orientation: Int = 0,

    // ── Text ──────────────────────────────────────────────────────────────
    /** Font size in px (14-48, default 22) */
    val fontSize: Int = 22,
    /** Font weight (100-900 step 100, default 400) */
    val fontWeight: Int = 400,
    /** Line height % (80-200, default 120) */
    val lineHeight: Int = 120,
    /** Page margin in px (0-50, default 20) */
    val margin: Int = 20,
    /**
     * CDN font face name matching FONT_FAMILIES keys in converter.js.
     * Empty string = renderer default.
     * Values: "Literata", "Lora", "Merriweather", "Open Sans", "Source Serif 4",
     *         "Noto Sans", "Noto Serif", "Roboto", "EB Garamond", "Crimson Pro"
     */
    val fontFace: String = "Literata",
    /**
     * Path to an imported custom TTF/OTF font file.
     * When set, overrides fontFace.
     */
    val fontFamily: String = "",
    /** Text alignment: -1=Default, 0=Left, 1=Right, 2=Center, 3=Justify */
    val textAlign: Int = -1,
    /** Word spacing %: 50, 75, 100, 125, 150, 200 */
    val wordSpacing: Int = 100,
    /** Hyphenation: 0=Off, 1=Algorithmic, 2=Dictionary */
    val hyphenation: Int = 2,
    /** Hyphenation language code, "auto" to detect from EPUB */
    val hyphenationLang: String = "auto",
    /** Strip document-defined margins so our margin setting is used exclusively */
    val ignoreDocMargins: Boolean = true,

    // ── Image ─────────────────────────────────────────────────────────────
    /** Color mode / quality */
    val colorMode: ColorMode = ColorMode.GRAYSCALE_4,
    val enableDithering: Boolean = true,
    /** Dithering strength 0-100 */
    val ditherStrength: Int = 70,
    /** Invert image (dark mode) */
    val enableNegative: Boolean = false,

    // ── Progress Bar ──────────────────────────────────────────────────────
    val enableProgressBar: Boolean = true,
    /** "bottom" or "top" */
    val progressPosition: String = "bottom",
    val showProgressLine: Boolean = true,
    val showChapterMarks: Boolean = true,
    val showChapterProgress: Boolean = false,
    val progressFullWidth: Boolean = false,
    val showPageInfo: Boolean = true,
    val showBookPercent: Boolean = true,
    val showChapterPage: Boolean = true,
    val showChapterPercent: Boolean = false,
    /** Progress bar font size px (10-20, default 14) */
    val progressFontSize: Int = 14,
    /** Progress bar edge margin px (0-30) */
    val progressEdgeMargin: Int = 0,
    /** Progress bar side margin px (0-30) */
    val progressSideMargin: Int = 0,
) {
    enum class ColorMode { MONOCHROME, GRAYSCALE_4 }

    val bookExtension: String get() = if (colorMode == ColorMode.MONOCHROME) "xtc" else "xtch"
    val deviceWidth: Int get() = 480
    val deviceHeight: Int get() = 800
}

sealed class ConverterStatus {
    object Idle : ConverterStatus()
    object ReadingFile : ConverterStatus()
    data class Converting(val progress: Int, val total: Int) : ConverterStatus()
    data class Success(val outputFile: File, val isSaved: Boolean = false) : ConverterStatus()
    data class Preview(val outputFile: File, val isSaved: Boolean = false) : ConverterStatus()
    object Uploading : ConverterStatus()
    object UploadSuccess : ConverterStatus()
    data class Error(val message: String) : ConverterStatus()
}

enum class ConverterUploadPhase {
    IDLE,
    QUEUED,
    UPLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ConverterUploadState(
    val phase: ConverterUploadPhase = ConverterUploadPhase.IDLE,
    val progress: Float = 0f,
    val error: String? = null
) {
    val isInProgress: Boolean
        get() = phase == ConverterUploadPhase.QUEUED || phase == ConverterUploadPhase.UPLOADING
}

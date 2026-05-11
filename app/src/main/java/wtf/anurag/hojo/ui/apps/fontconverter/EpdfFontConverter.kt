package wtf.anurag.hojo.ui.apps.fontconverter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

data class EpdfFontOptions(
        val sizePx: Int = 37,
        val bitsPerPixel: Int = 2
)

data class EpdfFontProgress(
        val current: Int,
        val total: Int
)

class EpdfFontConverter(private val context: Context) {
    private data class Interval(val start: Int, val end: Int)

    private data class GlyphProps(
            val width: Int,
            val height: Int,
            val advanceX: Int,
            val left: Int,
            val top: Int,
            val dataLength: Int,
            val dataOffset: Int
    )

    fun convert(
            fontFile: File,
            options: EpdfFontOptions = EpdfFontOptions(),
            onProgress: (EpdfFontProgress) -> Unit = {}
    ): ByteArray {
        val typeface = Typeface.Builder(fontFile).build()
        val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = options.sizePx.toFloat()
                    this.typeface = typeface
                    isSubpixelText = false
                }

        val codePoints = availableCodePoints(paint, onProgress)
        val intervals = intervalsFromCodePoints(codePoints)
        val glyphs = mutableListOf<GlyphProps>()
        val bitmapData = ByteArrayOutputStream()
        val bounds = Rect()

        codePoints.forEachIndexed { index, codePoint ->
            val text = String(Character.toChars(codePoint))
            paint.getTextBounds(text, 0, text.length, bounds)

            val width = bounds.width().coerceIn(0, 255)
            val height = bounds.height().coerceIn(0, 255)
            val advanceX = ceil(paint.measureText(text).toDouble()).toInt().coerceIn(0, 255)
            val left = bounds.left
            val top = -bounds.top
            val packed = renderGlyph(text, paint, bounds, width, height, options.bitsPerPixel)

            glyphs +=
                    GlyphProps(
                            width = width,
                            height = height,
                            advanceX = advanceX,
                            left = left,
                            top = top,
                            dataLength = packed.size,
                            dataOffset = bitmapData.size()
                    )
            bitmapData.write(packed)

            if (index % 128 == 0) {
                onProgress(EpdfFontProgress(index + 1, codePoints.size))
            }
        }

        val fontMetrics = paint.fontMetricsInt
        return buildBinary(
                intervals = intervals,
                glyphs = glyphs,
                bitmapData = bitmapData.toByteArray(),
                advanceY = (fontMetrics.bottom - fontMetrics.top).coerceIn(0, 255),
                ascender = (-fontMetrics.ascent).coerceIn(-128, 127),
                descender = fontMetrics.descent.coerceIn(-128, 127),
                is2Bit = options.bitsPerPixel == 2
        )
    }

    private fun availableCodePoints(
            paint: Paint,
            onProgress: (EpdfFontProgress) -> Unit
    ): List<Int> {
        val candidateIntervals =
                listOf(
                        Interval(0x0000, 0x007F),
                        Interval(0x0080, 0x00FF),
                        Interval(0x0100, 0x017F),
                        Interval(0x0300, 0x036F),
                        Interval(0x2000, 0x206F),
                        Interval(0x2070, 0x209F),
                        Interval(0x20A0, 0x20CF),
                        Interval(0x2190, 0x21FF),
                        Interval(0x2200, 0x22FF),
                        Interval(0x3000, 0x303F),
                        Interval(0x3400, 0x4DBF),
                        Interval(0x4E00, 0x9FFF),
                        Interval(0xFE10, 0xFE1F),
                        Interval(0xFE30, 0xFE4F),
                        Interval(0xFF00, 0xFFEF),
                        Interval(0xFFFD, 0xFFFD)
                )
        val total = candidateIntervals.sumOf { it.end - it.start + 1 }
        val result = mutableListOf<Int>()
        var scanned = 0

        for (interval in candidateIntervals) {
            for (codePoint in interval.start..interval.end) {
                val text = String(Character.toChars(codePoint))
                if (paint.hasGlyph(text)) {
                    result += codePoint
                }
                scanned++
                if (scanned % 256 == 0) {
                    onProgress(EpdfFontProgress(scanned, total))
                }
            }
        }

        onProgress(EpdfFontProgress(total, total))
        return result
    }

    private fun intervalsFromCodePoints(codePoints: List<Int>): List<Interval> {
        if (codePoints.isEmpty()) return emptyList()
        val intervals = mutableListOf<Interval>()
        var start = codePoints.first()
        var previous = start

        codePoints.drop(1).forEach { codePoint ->
            if (codePoint == previous + 1) {
                previous = codePoint
            } else {
                intervals += Interval(start, previous)
                start = codePoint
                previous = codePoint
            }
        }
        intervals += Interval(start, previous)
        return intervals
    }

    private fun renderGlyph(
            text: String,
            paint: Paint,
            bounds: Rect,
            width: Int,
            height: Int,
            bitsPerPixel: Int
    ): ByteArray {
        if (width == 0 || height == 0) return ByteArray(0)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawText(text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)

        val packed = ByteArrayOutputStream()
        var current = 0
        var count = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                if (bitsPerPixel == 2) {
                    val value =
                            when {
                                alpha >= 192 -> 3
                                alpha >= 128 -> 2
                                alpha >= 64 -> 1
                                else -> 0
                            }
                    current = (current shl 2) or value
                    count++
                    if (count == 4) {
                        packed.write(current)
                        current = 0
                        count = 0
                    }
                } else {
                    current = (current shl 1) or if (alpha >= 64) 1 else 0
                    count++
                    if (count == 8) {
                        packed.write(current)
                        current = 0
                        count = 0
                    }
                }
            }
        }

        if (count > 0) {
            current =
                    if (bitsPerPixel == 2) {
                        current shl ((4 - count) * 2)
                    } else {
                        current shl (8 - count)
                    }
            packed.write(current)
        }

        bitmap.recycle()
        return packed.toByteArray()
    }

    private fun buildBinary(
            intervals: List<Interval>,
            glyphs: List<GlyphProps>,
            bitmapData: ByteArray,
            advanceY: Int,
            ascender: Int,
            descender: Int,
            is2Bit: Boolean
    ): ByteArray {
        val headerSize = 32
        val intervalSize = 12
        val glyphSize = 16
        val offsetIntervals = headerSize
        val offsetGlyphs = offsetIntervals + intervals.size * intervalSize
        val offsetBitmaps = offsetGlyphs + glyphs.size * glyphSize

        val out = ByteArrayOutputStream(offsetBitmaps + bitmapData.size)
        out.write(byteArrayOf('E'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte()))
        out.writeShortLe(1)
        out.write(if (is2Bit) 1 else 0)
        out.write(0)
        out.write(advanceY and 0xFF)
        out.write(ascender.toByte().toInt() and 0xFF)
        out.write(descender.toByte().toInt() and 0xFF)
        out.write(0)
        out.writeIntLe(intervals.size)
        out.writeIntLe(glyphs.size)
        out.writeIntLe(offsetIntervals)
        out.writeIntLe(offsetGlyphs)
        out.writeIntLe(offsetBitmaps)

        var glyphOffset = 0
        intervals.forEach { interval ->
            out.writeIntLe(interval.start)
            out.writeIntLe(interval.end)
            out.writeIntLe(glyphOffset)
            glyphOffset += interval.end - interval.start + 1
        }

        glyphs.forEach { glyph ->
            out.write(glyph.width and 0xFF)
            out.write(glyph.height and 0xFF)
            out.write(glyph.advanceX and 0xFF)
            out.write(0)
            out.writeShortLe(glyph.left)
            out.writeShortLe(glyph.top)
            out.writeIntLe(glyph.dataLength)
            out.writeIntLe(glyph.dataOffset)
        }

        out.write(bitmapData)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }
}

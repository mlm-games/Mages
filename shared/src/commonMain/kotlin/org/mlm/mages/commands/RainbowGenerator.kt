package org.mlm.mages.commands

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object RainbowGenerator {
    fun generate(text: String): String {
        val parts = splitGraphemes(text)
        val frequency = 2 * PI / parts.size.coerceAtLeast(1)
        return parts.mapIndexed { index, part ->
            if (part == " ") {
                part
            } else {
                val (a, b) = generateAB(index * frequency)
                val color = labToRgb(75, a, b)
                "<font color=\"#${color}\">${escapeHtml(part)}</font>"
            }
        }.joinToString("")
    }

    private fun generateAB(hue: Double): Pair<Double, Double> =
        127.0 * cos(hue) to 127.0 * sin(hue)

    private fun labToRgb(l: Int, a: Double, b: Double): String {
        var y = (l + 16) / 116.0
        val x = adjustXyz(y + a / 500) * 0.9505
        val z = adjustXyz(y - b / 200) * 1.0890
        y = adjustXyz(y)

        val red = 3.24096994 * x - 1.53738318 * y - 0.49861076 * z
        val green = -0.96924364 * x + 1.8759675 * y + 0.04155506 * z
        val blue = 0.05563008 * x - 0.20397696 * y + 1.05697151 * z

        return buildString {
            append(adjustRgb(red).hex())
            append(adjustRgb(green).hex())
            append(adjustRgb(blue).hex())
        }
    }

    private fun adjustXyz(value: Double): Double =
        if (value > 0.2069) value.pow(3) else 0.1284 * value - 0.01771

    private fun adjustRgb(value: Double): Int = (gammaCorrect(value).coerceIn(0.0, 1.0) * 255).roundToInt()

    private fun gammaCorrect(value: Double): Double =
        if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1 / 2.4) - 0.055

    private fun Int.hex(): String = toString(16).padStart(2, '0')

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun splitGraphemes(text: String): List<String> {
        val result = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val first = text[index]
            if (first.code in 0xD800..0xDBFF && index + 1 < text.length) {
                val second = text[index + 1]
                if (second.code in 0xDC00..0xDFFF) {
                    result.add(text.substring(index, index + 2))
                    index += 2
                    continue
                }
            }
            result.add(first.toString())
            index++
        }
        return result
    }
}

package org.mlm.mages.ui.components.core

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattedBodyBlockTest {
    @Test
    fun aHorizontalRuleSeparatesTheBlocks() {
        assertEquals(
            "a\n\nb",
            parseFormattedBody("<p>a</p><hr><p>b</p>").text.text
        )
    }

    @Test
    fun divsStartTheirOwnLines() {
        assertEquals(
            "one\ntwo",
            parseFormattedBody("<div>one</div><div>two</div>").text.text
        )
    }

    @Test
    fun headingsAreBoldAndScaled() {
        val parsed = parseFormattedBody("<h1>Big</h1>", baseFontSize = 10.sp)
        val style = parsed.text.spanStyles.single { it.start == 0 && it.end == 3 }
        assertEquals(FontWeight.Bold, style.item.fontWeight)
        assertEquals(20.sp, style.item.fontSize)
    }

    @Test
    fun subscriptsShiftAndShrink() {
        val parsed = parseFormattedBody("H<sub>2</sub>O", baseFontSize = 10.sp)
        assertEquals("H2O", parsed.text.text)
        val style = parsed.text.spanStyles.single { it.start == 1 && it.end == 2 }
        assertEquals(BaselineShift.Subscript, style.item.baselineShift)
        assertEquals(7.5.sp, style.item.fontSize)
    }
}

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.mlm.mages.matrix

import kotlin.js.JsAny
import kotlin.js.get
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals

private fun jsTypeOf(value: JsAny?): String = js("(typeof value)")

class WasmInteropTest {
    @Test
    fun stringArraysContainPrimitiveStrings() {
        val values = listOf("event", "!").toJsArray()

        assertEquals("string", jsTypeOf(values[0]))
        assertEquals("string", jsTypeOf(values[1]))
    }

    @Test
    fun numberArraysContainPrimitiveNumbers() {
        val values = listOf(1.0, 2.0).toJsArray()

        assertEquals("number", jsTypeOf(values[0]))
        assertEquals("number", jsTypeOf(values[1]))
    }
}

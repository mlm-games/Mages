package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

actual object LocalAppLocale {
    private val appLocale = staticCompositionLocalOf { Locale.current }

    actual val current: String
        @Composable get() = appLocale.current.toLanguageTag()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        updateCustomLocale(value?.replace('_', '-'))
        return appLocale.provides(Locale.current)
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun updateCustomLocale(value: String?) {
    js("if (window.__customLocale !== value) { window.__customLocale = value; window.dispatchEvent(new Event(\"languagechange\")); }")
}

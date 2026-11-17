package org.mlm.mages.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.mlmgames.settings.core.SettingsSchema
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.platform.LocalAppLocale

private class ComposeSettingsStringResourceProvider(
    private val strings: Map<String, String>,
    private val arrays: Map<String, List<String>>,
) : StringResourceProvider {
    override fun getString(resId: Int): String = ""

    override fun getString(resId: Int, vararg formatArgs: Any): String = ""

    override fun getStringArray(resId: Int): List<String> = emptyList()

    override fun getString(key: String): String = strings[key].orEmpty()

    override fun getString(key: String, vararg formatArgs: Any): String {
        val value = getString(key)
        if (formatArgs.isEmpty()) return value
        return FORMAT_REGEX.replace(value) { match ->
            formatArgs.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value
        }
    }

    override fun getStringArray(key: String): List<String> = arrays[key].orEmpty()

    private companion object {
        val FORMAT_REGEX = Regex("""%(\d+)\$[ds]""")
    }
}

private fun SettingsSchema<*>.localizationKeys(): List<String> = buildList {
    fields.forEach { field ->
        val meta = field.meta ?: return@forEach
        add(meta.titleKey)
        add(meta.descriptionKey)
        add(meta.optionsKey)
    }
    addAll(categoryTitleKeys.values)
}.filter { it.isNotBlank() }.distinct()

@Composable
fun rememberSettingsStringResourceProvider(schema: SettingsSchema<*>): StringResourceProvider {
    val locale = LocalAppLocale.current
    val keys = remember(schema) { schema.localizationKeys() }
    val strings = keys.mapNotNull { key ->
        Res.allStringResources[key]?.let { key to stringResource(it) }
    }.toMap()
    val arrays = keys.mapNotNull { key ->
        Res.allStringArrayResources[key]?.let { key to stringArrayResource(it) }
    }.toMap()
    return remember(locale, strings, arrays) {
        ComposeSettingsStringResourceProvider(strings, arrays)
    }
}

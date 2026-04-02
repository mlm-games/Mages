package org.mlm.mages.settings

import kotlinx.serialization.Serializable

@Serializable
enum class AppLanguage(val languageTag: String?) {
    System(null),
    English("en"),
    Spanish("es")
}

fun AppSettings.appLanguage(): AppLanguage = language

fun AppSettings.appLanguageTagOrNull(): String? = language.languageTag

fun appLanguageTagOrDefault(language: AppLanguage?, defaultTag: String): String =
    language?.languageTag ?: defaultTag

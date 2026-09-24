package org.mlm.mages.settings

import kotlinx.serialization.Serializable

@Serializable
enum class AppLanguage(val languageTag: String?) {
    System(null),
    English("en"),
    Spanish("es"),
    Arabic("ar"),
    Czech("cs"),
    German("de"),
    Greek("el"),
    Persian("fa"),
    Finnish("fi"),
    French("fr"),
    Croatian("hr"),
    Hungarian("hu"),
    Indonesian("id"),
    Italian("it"),
    Hebrew("he"),
    Japanese("ja"),
    Korean("ko"),
    Dutch("nl"),
    Polish("pl"),
    Portuguese("pt"),
    Russian("ru"),
    Swedish("sv"),
    Turkish("tr"),
    Ukrainian("uk"),
    Vietnamese("vi"),
    ChineseSimplified("zh-CN"),
    ChineseTraditional("zh-TW")
}

fun AppSettings.appLanguage(): AppLanguage = language

fun AppSettings.appLanguageTagOrNull(): String? = language.languageTag

fun appLanguageTagOrDefault(language: AppLanguage?, defaultTag: String): String =
    language?.languageTag ?: defaultTag

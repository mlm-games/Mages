package org.mlm.mages.accounts

internal actual object BrowserAccountStorage {
    actual val isAvailable: Boolean = false

    actual fun getAccountsJson(): String? = null
    actual fun setAccountsJson(value: String) = Unit

    actual fun getActiveAccountId(): String? = null
    actual fun setActiveAccountId(value: String?) = Unit
}

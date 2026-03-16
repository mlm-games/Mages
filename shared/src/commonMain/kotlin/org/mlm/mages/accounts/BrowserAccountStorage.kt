package org.mlm.mages.accounts

internal expect object BrowserAccountStorage {
    val isAvailable: Boolean

    fun getAccountsJson(): String?
    fun setAccountsJson(value: String)

    fun getActiveAccountId(): String?
    fun setActiveAccountId(value: String?)
}

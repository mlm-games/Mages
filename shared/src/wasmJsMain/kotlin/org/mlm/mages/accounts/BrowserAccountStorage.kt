package org.mlm.mages.accounts

import kotlinx.browser.window

internal actual object BrowserAccountStorage {
    private const val ACCOUNTS_KEY = "mages.accountsJson"
    private const val ACTIVE_ID_KEY = "mages.activeAccountId"

    actual val isAvailable: Boolean
        get() = runCatching {
            window.localStorage.length
            true
        }.getOrDefault(false)

    actual fun getAccountsJson(): String? =
        runCatching { window.localStorage.getItem(ACCOUNTS_KEY) }.getOrNull()

    actual fun setAccountsJson(value: String) {
        runCatching {
            if (value.isBlank()) {
                window.localStorage.removeItem(ACCOUNTS_KEY)
            } else {
                window.localStorage.setItem(ACCOUNTS_KEY, value)
            }
        }
    }

    actual fun getActiveAccountId(): String? =
        runCatching { window.localStorage.getItem(ACTIVE_ID_KEY) }.getOrNull()

    actual fun setActiveAccountId(value: String?) {
        runCatching {
            if (value.isNullOrBlank()) {
                window.localStorage.removeItem(ACTIVE_ID_KEY)
            } else {
                window.localStorage.setItem(ACTIVE_ID_KEY, value)
            }
        }
    }
}

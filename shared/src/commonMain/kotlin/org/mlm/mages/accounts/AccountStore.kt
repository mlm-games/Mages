package org.mlm.mages.accounts

import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mlm.mages.settings.AppSettings

class AccountStore(
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private val _accounts = MutableStateFlow<List<MatrixAccount>>(emptyList())
    val accounts: StateFlow<List<MatrixAccount>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    suspend fun init() {
        val settings = settingsRepository.flow.first()
        _accounts.value = decodeAccounts(settings.accountsJson)
        _activeAccountId.value = settings.activeAccountId
    }

    private fun decodeAccounts(raw: String?): List<MatrixAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<MatrixAccount>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveAccounts(accounts: List<MatrixAccount>) {
        val encoded = json.encodeToString(accounts)
        settingsRepository.update { it.copy(accountsJson = encoded) }
        _accounts.value = accounts
    }

    fun getActiveAccount(): MatrixAccount? {
        val id = _activeAccountId.value ?: return null
        return _accounts.value.find { it.id == id }
    }

    suspend fun setActiveAccountId(id: String?) {
        settingsRepository.update { it.copy(activeAccountId = id) }
        _activeAccountId.value = id
    }

    suspend fun addAccount(account: MatrixAccount) {
        val current = _accounts.value.toMutableList()
        current.removeAll { it.userId == account.userId }
        current.add(account)
        saveAccounts(current)
    }

    suspend fun updateAccount(account: MatrixAccount) {
        val current = _accounts.value.toMutableList()
        val idx = current.indexOfFirst { it.id == account.id }
        if (idx >= 0) {
            current[idx] = account
            saveAccounts(current)
        }
    }

    suspend fun removeAccount(accountId: String) {
        val current = _accounts.value.filter { it.id != accountId }
        saveAccounts(current)
        if (_activeAccountId.value == accountId) {
            setActiveAccountId(current.firstOrNull()?.id)
        }
    }

    fun getAccountById(id: String): MatrixAccount? = _accounts.value.find { it.id == id }

    fun getAccountByUserId(userId: String): MatrixAccount? = _accounts.value.find { it.userId == userId }

    fun hasAccounts(): Boolean = _accounts.value.isNotEmpty()
}
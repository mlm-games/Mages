package org.mlm.mages.accounts

import co.touchlab.kermit.Logger
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.deleteDirectory
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.toPresence
import kotlin.concurrent.Volatile

internal fun Throwable.isTlsUnavailable(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current::class.simpleName == "TlsUnavailableException") return true
        current = current.cause
    }
    return false
}

class MatrixClients(
    private val accountStore: AccountStore,
    private val settingsRepository: SettingsRepository<AppSettings>
) {
    private val mutex = Mutex()

    private val _activeAccount = MutableStateFlow<MatrixAccount?>(null)
    val activeAccount: StateFlow<MatrixAccount?> = _activeAccount.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var _activePort: MatrixPort? = null

    val port: MatrixPort
        get() = _activePort ?: error("No active Matrix client. Call initFromDisk() or switchTo() first.")

    val portOrNull: MatrixPort?
        get() = _activePort

    /**
     * Restores the previously active account if available.
     */
    suspend fun initFromDisk(proxyUrl: String? = null): Boolean = mutex.withLock {
        // Already initialized
        _activePort?.let {
            _isReady.value = true
            return@withLock hasActiveClient()
        }

        accountStore.init()

        val activeId = accountStore.activeAccountId.value
        val account = if (activeId != null) {
            accountStore.getAccountById(activeId)
        } else {
            accountStore.accounts.value.firstOrNull()
        }

        if (account != null) {
            return@withLock initAccountInternal(account, proxyUrl)
        }

        // No accounts, so log in
        _isReady.value = true
        false
    }

    suspend fun switchTo(account: MatrixAccount, proxyUrl: String? = null): Boolean = mutex.withLock {
        // Close current client if any
        _activePort?.let { port ->
            runCatching { port.release() }
        }
        _activePort = null
        _activeAccount.value = null

        return@withLock initAccountInternal(account, proxyUrl)
    }

    /**
     * The port should already be logged in.
     */
    suspend fun addLoggedInAccount(account: MatrixAccount, port: MatrixPort) = mutex.withLock {
        accountStore.init()
        _activePort?.let { runCatching { it.release() } }

        val existing = accountStore.getAccountByUserId(account.userId)
        accountStore.addAccount(account)
        if (existing != null && existing.id != account.id) {
            runCatching { deleteDirectory(accountStoreDir(existing.id)) }
            deleteSessionFiles(existing.id)
        }
        accountStore.setActiveAccountId(account.id)

        _activePort = port
        _activeAccount.value = account
        applyStoredPresence(port)
        _isReady.value = true
    }

    suspend fun logoutCurrent() = mutex.withLock {
        val current = _activeAccount.value ?: return@withLock

        _activePort?.let {
            runCatching { it.logout() }
            // Waits for the store handles, the directory below is theirs.
            runCatching { it.release() }
        }
        _activePort = null
        _activeAccount.value = null

        accountStore.removeAccount(current.id)
        runCatching { deleteDirectory(accountStoreDir(current.id)) }
        deleteSessionFiles(current.id)

        val next = accountStore.accounts.value.firstOrNull()
        if (next != null) {
            initAccountInternal(next)
        } else {
            _isReady.value = true
        }
    }

    suspend fun removeAccount(accountId: String) = mutex.withLock {
        val wasActive = _activeAccount.value?.id == accountId

        if (wasActive) {
            _activePort?.let { port ->
                runCatching { port.logout() }
                runCatching { port.release() }
            }
            _activePort = null
            _activeAccount.value = null
        }

        accountStore.removeAccount(accountId)

        val storeDir = accountStoreDir(accountId)
        runCatching {
            deleteDirectory(storeDir)
        }
        deleteSessionFiles(accountId)

        if (wasActive) {
            val nextAccount = accountStore.accounts.value.firstOrNull()
            if (nextAccount != null) {
                initAccountInternal(nextAccount)
            }
        }
    }

    fun hasActiveClient(): Boolean {
        val port = _activePort ?: return false
        val sdkLoggedIn = runCatching { port.isLoggedIn() }.getOrDefault(false)
        if (sdkLoggedIn) return true
        return _activeAccount.value?.isTokenValid == true
    }

    fun getAccounts(): List<MatrixAccount> = accountStore.accounts.value

    private suspend fun initAccountInternal(account: MatrixAccount, proxyUrl: String? = null): Boolean {
        _activePort?.let { runCatching { it.release() } }
        _activePort = null
        _activeAccount.value = null

        val port = createMatrixPort()
        val effectiveProxy = proxyUrl ?: account.proxyUrl

        return try {
            port.init(account.homeserver, account.id, effectiveProxy, account.enableShareHistoryOnInvite)
            applyStoredPresence(port)

            val loggedIn = try {
                port.isLoggedInSuspend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }

            val shouldResumeSession = loggedIn || account.isTokenValid

            if (shouldResumeSession) {
                _activePort = port
                _activeAccount.value = account
                accountStore.setActiveAccountId(account.id)
                _isReady.value = true
                true
            } else {
                runCatching { port.release() }
                _isReady.value = true
                false
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { port.release() } }
            _isReady.value = true
            throw e
        } catch (e: Exception) {
            if (e.isTlsUnavailable()) {
                Logger.e(e) { "MatrixClients: TLS unavailable, session resume failed for ${account.userId}" }
            } else {
                Logger.w(e) { "MatrixClients: session resume failed for ${account.userId}" }
            }
            runCatching { port.release() }
            _isReady.value = true
            false
        }
    }

    private suspend fun applyStoredPresence(port: MatrixPort) {
        try {
            port.applySyncPresence(settingsRepository.flow.first().presence.toPresence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "MatrixClients: presence restore skipped" }
        }
    }

    private fun accountStoreDir(accountId: String): String {
        val base = MagesPaths.storeDir()
        return "$base/accounts/$accountId"
    }

    private fun deleteSessionFiles(accountId: String) {
        val base = MagesPaths.storeDir()
        runCatching { deleteDirectory("$base/accounts/$accountId.session.json") }
        runCatching { deleteDirectory("$base/accounts/$accountId.session.tmp") }
    }
}

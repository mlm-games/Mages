package org.mlm.mages.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.createMatrixPort
import org.mlm.mages.platform.MagesPaths
import org.mlm.mages.platform.deleteDirectory
import kotlin.concurrent.Volatile

class MatrixClients(
    private val accountStore: AccountStore
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
    suspend fun initFromDisk(): Boolean = mutex.withLock {
        // Already initialized
        _activePort?.let { existing ->
            _isReady.value = true
            return@withLock existing.isLoggedIn()
        }

        accountStore.init()

        val activeId = accountStore.activeAccountId.value
        val account = if (activeId != null) {
            accountStore.getAccountById(activeId)
        } else {
            accountStore.accounts.value.firstOrNull()
        }

        if (account != null) {
            return@withLock initAccountInternal(account)
        }

        // No accounts, so log in
        _isReady.value = true
        false
    }

    suspend fun switchTo(account: MatrixAccount): Boolean = mutex.withLock {
        // Close current client if any
        _activePort?.let { port ->
            runCatching { port.close() }
        }
        _activePort = null
        _activeAccount.value = null

        return@withLock initAccountInternal(account)
    }

    /**
     * The port should already be logged in.
     */
    suspend fun addLoggedInAccount(account: MatrixAccount, port: MatrixPort) = mutex.withLock {
        _activePort?.let { runCatching { it.close() } }

        val existing = accountStore.getAccountByUserId(account.userId)
        if (existing != null && existing.id != account.id) {
            accountStore.removeAccount(existing.id)
            runCatching { deleteDirectory(accountStoreDir(existing.id)) }
        }

        accountStore.addAccount(account)
        accountStore.setActiveAccountId(account.id)

        _activePort = port
        _activeAccount.value = account
        _isReady.value = true
    }

    suspend fun logoutCurrent() = mutex.withLock {
        val current = _activeAccount.value ?: return@withLock

        _activePort?.let {
            runCatching { it.logout() }
            runCatching { it.close() }
        }
        _activePort = null
        _activeAccount.value = null

        accountStore.removeAccount(current.id)
        runCatching { deleteDirectory(accountStoreDir(current.id)) }

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
                runCatching { port.close() }
            }
            _activePort = null
            _activeAccount.value = null
        }

        accountStore.removeAccount(accountId)

        val storeDir = accountStoreDir(accountId)
        runCatching {
            deleteDirectory(storeDir)
        }

        if (wasActive) {
            val nextAccount = accountStore.accounts.value.firstOrNull()
            if (nextAccount != null) {
                initAccountInternal(nextAccount)
            }
        }
    }

    fun hasActiveClient(): Boolean = _activePort?.isLoggedIn() == true

    fun getAccounts(): List<MatrixAccount> = accountStore.accounts.value

    private suspend fun initAccountInternal(account: MatrixAccount): Boolean {
        _activePort?.let { runCatching { it.close() } }
        _activePort = null
        _activeAccount.value = null

        val port = createMatrixPort()

        return try {
            port.init(account.homeserver, account.id)

            if (port.isLoggedIn()) {
                _activePort = port
                _activeAccount.value = account
                accountStore.setActiveAccountId(account.id)
                _isReady.value = true
                true
            } else {
                accountStore.removeAccount(account.id)
                runCatching { port.close() }
                _isReady.value = true
                false
            }
        } catch (_: Exception) {
            runCatching { port.close() }
            _isReady.value = true
            false
        }
    }

    private fun accountStoreDir(accountId: String): String {
        val base = MagesPaths.storeDir()
        return "$base/accounts/$accountId"
    }
}
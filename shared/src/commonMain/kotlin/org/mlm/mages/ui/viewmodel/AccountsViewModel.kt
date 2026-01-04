package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.mlm.mages.MatrixService
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.accounts.MatrixAccount

data class AccountsUiState(
    val accounts: List<MatrixAccount> = emptyList(),
    val activeAccountId: String? = null,
    val isSwitching: Boolean = false,
    val error: String? = null
)

class AccountsViewModel(
    private val service: MatrixService,
    private val accountStore: AccountStore
) : BaseViewModel<AccountsUiState>(AccountsUiState()) {

    sealed class Event {
        data object AccountSwitched : Event()
        data object AccountRemoved : Event()
        data object AddAccountRequested : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        launch {
            accountStore.accounts.collect { accounts ->
                updateState { copy(accounts = accounts) }
            }
        }

        launch {
            accountStore.activeAccountId.collect { id ->
                updateState { copy(activeAccountId = id) }
            }
        }
    }

    fun switchAccount(account: MatrixAccount) {
        if (account.id == currentState.activeAccountId) return

        launch(onError = { t ->
            updateState { copy(isSwitching = false, error = t.message) }
            launch { _events.send(Event.ShowError(t.message ?: "Failed to switch account")) }
        }) {
            updateState { copy(isSwitching = true, error = null) }

            val success = service.switchAccount(account)

            updateState { copy(isSwitching = false) }

            if (success) {
                _events.send(Event.AccountSwitched)
            } else {
                _events.send(Event.ShowError("Failed to switch to ${account.userId}"))
            }
        }
    }

    fun removeAccount(account: MatrixAccount) {
        launch(onError = { t ->
            launch { _events.send(Event.ShowError(t.message ?: "Failed to remove account")) }
        }) {
            service.removeAccount(account.id)
            _events.send(Event.AccountRemoved)
        }
    }

    fun addAccount() {
        launch {
            _events.send(Event.AddAccountRequested)
        }
    }

    fun clearError() {
        updateState { copy(error = null) }
    }
}
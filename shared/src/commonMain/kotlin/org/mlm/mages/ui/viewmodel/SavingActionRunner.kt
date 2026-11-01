package org.mlm.mages.ui.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal class SavingActionRunner(
    private val scope: CoroutineScope,
    private val setSaving: (Boolean) -> Unit,
    private val emitError: suspend (String) -> Unit,
    private val emitSuccess: suspend (String) -> Unit,
) {

    fun runBoolean(
        successMessage: StringResource,
        errorMessage: StringResource,
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Boolean,
    ) {
        scope.launch {
            setSaving(true)
            val ok = try {
                block()
            } catch (e: CancellationException) {
                setSaving(false)
                throw e
            } catch (t: Throwable) {
                setSaving(false)
                emitError(t.failureMessage(getString(errorMessage)))
                return@launch
            }
            setSaving(false)

            if (ok) {
                onSuccess?.invoke()
                emitSuccess(getString(successMessage))
            } else {
                emitError(getString(errorMessage))
            }
        }
    }

    fun runResult(
        errorMessage: StringResource,
        errorMessageFor: suspend (Throwable) -> String? = { null },
        onSuccess: (suspend () -> Unit)? = null,
        block: suspend () -> Result<Unit>?,
    ) {
        scope.launch {
            setSaving(true)
            val result = try {
                block()
            } catch (e: CancellationException) {
                setSaving(false)
                throw e
            } catch (t: Throwable) {
                setSaving(false)
                emitError(errorMessageFor(t) ?: t.failureMessage(getString(errorMessage)))
                return@launch
            }
            setSaving(false)

            if (result?.isSuccess == true) {
                onSuccess?.invoke()
            } else {
                val specific = result?.exceptionOrNull()?.let { errorMessageFor(it) }
                emitError(specific ?: result.toUserMessage(getString(errorMessage)))
            }
        }
    }
}

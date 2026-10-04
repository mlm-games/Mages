package org.mlm.mages.nav

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object PendingDeepLinks {
    private val channel = Channel<String>(Channel.BUFFERED)

    val links: Flow<String> = channel.receiveAsFlow()

    fun offer(rawLink: String) {
        channel.trySend(rawLink)
    }
}

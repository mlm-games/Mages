package org.mlm.mages.push

object CallTelecomBridge {
    var onIncomingGone: ((roomId: String) -> Unit)? = null
}

package org.mlm.mages.push
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object LinuxPushManager {
    private const val CONNECTOR_PATH = "/org/unifiedpush/Connector"
    private const val DISTRIBUTOR_PATH = "/org/unifiedpush/Distributor"
    private val DISTRIBUTOR_BUSES = listOf(
        "org.unifiedpush.Distributor.ntfy",
        "org.unifiedpush.Distributor.sunup",
        "org.unifiedpush.Distributor.gcompat",
        "org.unifiedpush.Distributor.kde",
        "eu.uniformpush.distributor"
    )
    private const val REGISTRATION_SUCCEEDED = "REGISTRATION_SUCCEEDED"

    private var conn: DBusConnection? = null
    private var token: String? = null
    private var endpointFuture: CompletableFuture<String>? = null
    private var onMessage: ((String) -> Unit)? = null

    /** Connector v1 — distributor calls this with (token, endpoint) etc. */
    @DBusInterfaceName("org.unifiedpush.Connector1")
    class Connector1(
        private val tokenProvider: () -> String?,
        private val onEndpoint: (String) -> Unit,
        private val onPushMessage: (String) -> Unit
    ) : DBusInterface {
        override fun getObjectPath() = CONNECTOR_PATH

        fun NewEndpoint(token: String, endpoint: String) {
            if (token == tokenProvider()) onEndpoint(endpoint)
        }

        fun Message(token: String, message: ByteArray, messageIdentifier: String) {
            if (token == tokenProvider()) {
                onPushMessage(String(message, Charsets.UTF_8))
            }
        }

        fun Unregistered(token: String) {}
    }

    @DBusInterfaceName("org.unifiedpush.Distributor1")
    interface Distributor1 : DBusInterface {
        fun Register(serviceName: String, token: String, description: String): RegisterResult
    }

    @DBusInterfaceName("org.unifiedpush.Distributor2")
    interface Distributor2 : DBusInterface {
        fun Register(args: Map<String, Variant<String>>): Map<String, Variant<String>>
    }

    suspend fun tryRegister(): String? = withContext(Dispatchers.IO) {
        try {
            val c = DBusConnectionBuilder.forSessionBus().build()
            val suffix = UUID.randomUUID().toString()
            val busName = "org.mlm.mages.Instance${suffix.take(8)}"
            c.requestBusName(busName)
            Logger.w("[UP] bus name acquired: $busName")

            val future = CompletableFuture<String>()
            endpointFuture = future
            val tok = UUID.randomUUID().toString()
            token = tok

            c.exportObject(
                CONNECTOR_PATH,
                Connector1(
                    tokenProvider = { token },
                    onEndpoint = { ep -> future.complete(ep) },
                    onPushMessage = { msg -> onMessage?.invoke(msg) }
                )
            )

            val registered = tryRegisterV1(c, busName, tok) || tryRegisterV2(c, busName, tok)

            if (!registered) {
                c.close()
                return@withContext null
            }

            conn = c
            val ep = try {
                future.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Logger.w("[UP] endpoint wait failed: ${e.message}", e)
                try { c.unexportObject(CONNECTOR_PATH) } catch (ce: Exception) {
                    Logger.d("[UP] unexport failed: ${ce.message}")
                }
                try { c.close() } catch (ce: Exception) {
                    Logger.d("[UP] close after timeout failed: ${ce.message}")
                }
                conn = null
                return@withContext null
            }
            Logger.w("[UP] endpoint received: $ep")
            ep
        } catch (e: Exception) {
            Logger.w("[UP] register failed: ${e.message}", e)
            conn?.let { try { it.close() } catch (ce: Exception) {
                Logger.d("[UP] close on error failed: ${ce.message}")
            } }
            conn = null
            null
        }
    }

    private fun tryRegisterV1(c: DBusConnection, busName: String, tok: String): Boolean {
        for (bus in DISTRIBUTOR_BUSES) {
            try {
                val d = c.getRemoteObject(bus, DISTRIBUTOR_PATH, Distributor1::class.java, false)
                val result = d.Register(busName, tok, "Mages Matrix Client")
                if (result.registrationResult == REGISTRATION_SUCCEEDED) return true
            } catch (e: Exception) {
                Logger.d("[UP] V1 $bus unavailable: ${e.message}")
            }
        }
        return false
    }

    private fun tryRegisterV2(c: DBusConnection, busName: String, tok: String): Boolean {
        for (bus in DISTRIBUTOR_BUSES) {
            try {
                val d = c.getRemoteObject(bus, DISTRIBUTOR_PATH, Distributor2::class.java, false)
                val args = mapOf(
                    "service" to Variant(busName),
                    "token" to Variant(tok),
                    "description" to Variant("Mages Matrix Client")
                )
                val result = d.Register(args)
                if (result["success"]?.getValue() == REGISTRATION_SUCCEEDED) return true
            } catch (e: Exception) {
                Logger.d("[UP] V2 $bus unavailable: ${e.message}")
            }
        }
        return false
    }

    fun onMessage(callback: (String) -> Unit) {
        onMessage = callback
    }

    val isConnected: Boolean get() = conn?.isConnected == true

    val currentEndpoint: String? get() = endpointFuture?.getNow(null)

    fun shutdown() {
        try { conn?.close() } catch (e: Exception) {
            Logger.d("[UP] shutdown close failed: ${e.message}")
        }
        conn = null
        endpointFuture = null
        token = null
        onMessage = null
    }
}

class RegisterResult private constructor() : Tuple() {
    @JvmField var registrationResult = ""
    @JvmField var registrationResultReason = ""

    constructor(vararg results: Any) : this() {
        if (results.size > 0) registrationResult = results[0] as String
        if (results.size > 1) registrationResultReason = results[1] as String
    }
}

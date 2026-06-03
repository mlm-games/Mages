package org.mlm.mages.push

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
    private const val DISTRIBUTOR_BUS = "org.unifiedpush.Distributor.kde"
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

    fun tryRegister(): String? {
        try {
            val c = DBusConnectionBuilder.forSessionBus().build()
            val suffix = UUID.randomUUID().toString()
            val busName = "org.mlm.mages.Instance${suffix.take(8)}"
            c.requestBusName(busName)
            System.err.println("[UP] bus name acquired: $busName")

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
                return null
            }

            conn = c
            val ep = future.get(30, TimeUnit.SECONDS)
            System.err.println("[UP] endpoint received: $ep")
            return ep
        } catch (e: Exception) {
            conn?.let { try { it.close() } catch (_: Exception) {} }
            conn = null
            return null
        }
    }

    private fun tryRegisterV1(c: DBusConnection, busName: String, tok: String): Boolean {
        return try {
            val d = c.getRemoteObject(DISTRIBUTOR_BUS, DISTRIBUTOR_PATH, Distributor1::class.java, false)
            val result = d.Register(busName, tok, "Mages Matrix Client")
            result.registrationResult == REGISTRATION_SUCCEEDED
        } catch (e: Exception) {
            false
        }
    }

    private fun tryRegisterV2(c: DBusConnection, busName: String, tok: String): Boolean {
        return try {
            val d = c.getRemoteObject(DISTRIBUTOR_BUS, DISTRIBUTOR_PATH, Distributor2::class.java, false)
            val args = mapOf(
                "service" to Variant(busName),
                "token" to Variant(tok),
                "description" to Variant("Mages Matrix Client")
            )
            val result = d.Register(args)
            result["success"]?.getValue() == REGISTRATION_SUCCEEDED
        } catch (e: Exception) {
            false
        }
    }

    fun onMessage(callback: (String) -> Unit) {
        onMessage = callback
    }

    val isConnected: Boolean get() = conn?.isConnected == true

    val currentEndpoint: String? get() = endpointFuture?.getNow(null)

    fun shutdown() {
        try { conn?.close() } catch (_: Exception) {}
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

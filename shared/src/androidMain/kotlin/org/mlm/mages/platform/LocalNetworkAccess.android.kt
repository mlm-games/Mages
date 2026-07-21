package org.mlm.mages.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

private const val ANDROID_17_SDK_INT = 37

private val localNetworkPermission: String
    get() = Manifest.permission.ACCESS_LOCAL_NETWORK

actual fun isLocalNetworkPermissionEnforced(): Boolean =
    Build.VERSION.SDK_INT >= ANDROID_17_SDK_INT

actual fun hasLocalNetworkPermission(): Boolean {
    if (!isLocalNetworkPermissionEnforced()) return true
    val context = runCatching { KoinPlatform.getKoin().get<android.content.Context>() }.getOrNull()
        ?: return false
    return ContextCompat.checkSelfPermission(context, localNetworkPermission) ==
        PackageManager.PERMISSION_GRANTED
}

actual suspend fun shouldRequestLocalNetworkPermission(homeserverUrl: String): Boolean {
    if (!isLocalNetworkPermissionEnforced()) return false
    if (hasLocalNetworkPermission()) return false
    return when (classifyHomeserver(homeserverUrl)) {
        LocalNetworkClassification.LocalIp -> true
        LocalNetworkClassification.PublicIp,
        LocalNetworkClassification.Unresolvable -> false
    }
}

actual fun openAppPermissionSettings() {
    val context = runCatching { KoinPlatform.getKoin().get<android.content.Context>() }.getOrNull()
        ?: return
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
actual fun rememberLocalNetworkPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onResult(granted) }

    return remember(launcher, context) {
        {
            if (!isLocalNetworkPermissionEnforced()) {
                onResult(true)
            } else if (
                ContextCompat.checkSelfPermission(context, localNetworkPermission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                onResult(true)
            } else {
                launcher.launch(localNetworkPermission)
            }
        }
    }
}

private enum class LocalNetworkClassification { PublicIp, LocalIp, Unresolvable }

private suspend fun classifyHomeserver(url: String): LocalNetworkClassification {
    val host = extractHost(url) ?: return LocalNetworkClassification.Unresolvable
    if (host.endsWith(".local", ignoreCase = true)) return LocalNetworkClassification.LocalIp

    val resolved = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
    }
    if (resolved.isNullOrEmpty()) return LocalNetworkClassification.Unresolvable

    return if (resolved.any { it.isLocalRange() }) {
        LocalNetworkClassification.LocalIp
    } else {
        LocalNetworkClassification.PublicIp
    }
}

private fun extractHost(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    return runCatching {
        val uri = URI(candidate)
        uri.host?.takeIf { it.isNotBlank() }
            ?: trimmed.substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun InetAddress.isLocalRange(): Boolean {
    if (isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress) return true
    if (this is Inet4Address) {
        val b0 = address[0].toInt() and 0xff
        val b1 = address[1].toInt() and 0xff
        if (b0 == 100 && b1 in 64..127) return true
    }
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xff
        if (first and 0xfe == 0xfc) return true
    }
    return false
}

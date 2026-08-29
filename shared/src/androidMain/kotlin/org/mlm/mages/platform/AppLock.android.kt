package org.mlm.mages.platform

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.mp.KoinPlatform

private fun Context.findActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is FragmentActivity) return ctx
        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
    }
    // Fallback to holder
    return CurrentActivityHolder.activity as? FragmentActivity
}

class AndroidAppLockController(
    private val appContext: Context,
) : AppLockController {

    private val _isLocked = MutableStateFlow(false)
    override val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    internal var enabled: Boolean = false
    internal var timeoutSeconds: Long = 60L
    internal var lastBackgroundedAtMs: Long = 0L
    internal var wasInBackground: Boolean = false
    internal var initialized: Boolean = false

    override val isAvailable: Boolean
        get() {
            val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isDeviceSecure = km?.isDeviceSecure == true
            if (!isDeviceSecure) return false
            return try {
                val bm = BiometricManager.from(appContext)
                val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                val res = bm.canAuthenticate(auth)
                res == BiometricManager.BIOMETRIC_SUCCESS
            } catch (_: Exception) {
                // Fallback: if device is secure we consider available
                true
            }
        }

    fun onAppBackgrounded() {
        if (!enabled) return
        wasInBackground = true
        lastBackgroundedAtMs = System.currentTimeMillis()
        if (timeoutSeconds == 0L) {
            _isLocked.value = true
        }
    }

    fun onAppForegrounded() {
        if (!enabled) {
            _isLocked.value = false
            wasInBackground = false
            return
        }
        if (!wasInBackground && initialized) return
        wasInBackground = false
        initialized = true
        // Cold start locks until auth
        if (lastBackgroundedAtMs == 0L) {
            _isLocked.value = true
            return
        }
        val elapsed = System.currentTimeMillis() - lastBackgroundedAtMs
        val shouldLock = when {
            timeoutSeconds < 0 -> false
            timeoutSeconds == 0L -> true
            else -> elapsed >= timeoutSeconds * 1000
        }
        if (shouldLock) {
            _isLocked.value = true
        }
    }

    fun onEnabledChanged(newEnabled: Boolean) {
        enabled = newEnabled
        if (!newEnabled) {
            _isLocked.value = false
            wasInBackground = false
            lastBackgroundedAtMs = 0L
            initialized = false
        } else {
            _isLocked.value = true
            initialized = true
        }
    }

    fun onTimeoutChanged(newTimeout: Long) {
        timeoutSeconds = newTimeout
    }

    override fun lockNow() {
        if (enabled) _isLocked.value = true
    }

    override fun requestUnlock(onResult: (Boolean) -> Unit) {
        val activity = CurrentActivityHolder.activity as? FragmentActivity
            ?: appContext.findActivity()
        if (activity == null) {
            onResult(false)
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                _isLocked.value = false
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }

            override fun onAuthenticationFailed() {
                // Not final..
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Mages")
            .setSubtitle("Confirm it's you")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()
        try {
            prompt.authenticate(promptInfo)
        } catch (_: Exception) {
            onResult(false)
        }
    }
}

actual fun createAppLockController(): AppLockController {
    val ctx = try {
        KoinPlatform.getKoin().get<Context>()
    } catch (_: Exception) {
        CurrentActivityHolder.activity?.applicationContext
            ?: throw IllegalStateException("AppLockController requires Context")
    }
    return AndroidAppLockController(ctx.applicationContext)
}

@Composable
actual fun BindAppLock(
    controller: AppLockController,
    enabled: Boolean,
    timeoutSeconds: Long,
) {
    val androidController = controller as? AndroidAppLockController ?: return
    DisposableEffect(enabled) {
        androidController.onEnabledChanged(enabled)
        onDispose {}
    }
    DisposableEffect(timeoutSeconds) {
        androidController.onTimeoutChanged(timeoutSeconds)
        onDispose {}
    }

    DisposableEffect(androidController) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                androidController.onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                androidController.onAppBackgrounded()
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            androidController.onAppForegrounded()
        }
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
}

@Composable
actual fun BindScreenSecurity(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context, enabled) {
        var ctx: Context? = context
        var found: FragmentActivity? = null
        while (ctx != null) {
            if (ctx is FragmentActivity) {
                found = ctx
                break
            }
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        }
        found ?: CurrentActivityHolder.activity as? FragmentActivity
    }
    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (window != null) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            // keep flag as is
        }
    }
}

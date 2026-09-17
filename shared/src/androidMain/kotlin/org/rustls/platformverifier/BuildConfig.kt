// NOTE: this file is a local adaptation shim.
//
// The vendored CertificateVerifier.kt (from rustls-platform-verifier) reads
// BuildConfig.TEST to select test-only mock trust-store behavior. This module has no generated
// BuildConfig in that package, so it is always false, (system trust manager only).

package org.rustls.platformverifier

internal object BuildConfig {
    const val TEST = false
}

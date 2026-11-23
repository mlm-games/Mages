package org.mlm.mages.platform

actual fun appVersion(): String = System.getProperty("jpackage.app.version") ?: "dev"

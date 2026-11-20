package org.mlm.mages.platform

actual fun createAudioPlayer(): AudioPlayer = GadulkaAudioPlayer()

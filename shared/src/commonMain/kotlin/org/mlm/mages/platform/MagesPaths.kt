package org.mlm.mages.platform

expect object MagesPaths {
    fun init()
    fun storeDir(): String
}
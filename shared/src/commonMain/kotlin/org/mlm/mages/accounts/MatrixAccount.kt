package org.mlm.mages.accounts

import kotlinx.serialization.Serializable

@Serializable
data class MatrixAccount(
    val id: String, // local ID (odDevice or generated UUID)
    val userId: String, // @user:server.com
    val homeserver: String, // https://matrix.org
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val displayName: String? = null,   // Cached display name
    val avatarUrl: String? = null,
    val addedAtMs: Long = 0 // Whenever this account was added
)
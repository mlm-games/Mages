package org.mlm.mages.matrix

import kotlinx.serialization.Serializable

const val INFINITE_POWER_LEVEL: Long = 2147483647L
const val SUPERADMIN_POWER_LEVEL: Long = 150L
const val ADMIN_POWER_LEVEL: Long = 100L
const val MODERATOR_POWER_LEVEL: Long = 50L

@Serializable
enum class MemberRole {
    Creator,
    Owner,
    Admin,
    Moderator,
    User,
    ;

    companion object {
        fun forPowerLevel(powerLevel: Long): MemberRole = when {
            powerLevel >= INFINITE_POWER_LEVEL -> Creator
            powerLevel >= SUPERADMIN_POWER_LEVEL -> Owner
            powerLevel >= ADMIN_POWER_LEVEL -> Admin
            powerLevel >= MODERATOR_POWER_LEVEL -> Moderator
            else -> User
        }
    }
}

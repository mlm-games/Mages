package org.mlm.mages.matrix

import kotlin.test.Test
import kotlin.test.assertEquals

class MemberRoleTest {
    @Test
    fun mapsPowerLevelsToRoles() {
        val cases = listOf(
            -100L to MemberRole.User,
            -1L to MemberRole.User,
            0L to MemberRole.User,
            49L to MemberRole.User,
            50L to MemberRole.Moderator,
            99L to MemberRole.Moderator,
            100L to MemberRole.Admin,
            149L to MemberRole.Admin,
            150L to MemberRole.Owner,
            200L to MemberRole.Owner,
            INFINITE_POWER_LEVEL to MemberRole.Creator,
        )
        cases.forEach { (powerLevel, expected) ->
            assertEquals(expected, MemberRole.forPowerLevel(powerLevel), "power level $powerLevel")
        }
    }
}

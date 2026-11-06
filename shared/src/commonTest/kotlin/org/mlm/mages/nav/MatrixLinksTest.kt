package org.mlm.mages.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MatrixLinksTest {

    private fun user(raw: String) = (parseMatrixLink(raw) as MatrixLink.User).mxid

    private fun room(raw: String) = (parseMatrixLink(raw) as MatrixLink.Room).target

    @Test
    fun matrixToAlias() {
        assertEquals("#mages:unredacted.org", room("https://matrix.to/#/#mages:unredacted.org").roomIdOrAlias)
        assertEquals("#mages:unredacted.org", room("https://matrix.to/#/%23mages%3Aunredacted.org").roomIdOrAlias)
        assertEquals("#mages:unredacted.org", room("https://matrix.to/#/%23mages%3Aunredacted.org?via=unredacted.org&via=matrix.org").roomIdOrAlias)
        assertEquals(
            listOf("unredacted.org", "matrix.org"),
            room("https://matrix.to/#/%23mages%3Aunredacted.org?via=unredacted.org&via=matrix.org").via,
        )
    }

    @Test
    fun matrixToRoomIdAndPermalink() {
        assertEquals("!room:server.example", room("https://matrix.to/#/!room:server.example").roomIdOrAlias)
        // room version 12: no domain in the room id
        assertEquals("!id", room("https://matrix.to/#/!id").roomIdOrAlias)
        assertEquals("!room:server.example", room("https://matrix.to/#/!room:server.example/\$event:server.example").roomIdOrAlias)
        assertEquals("\$event:server.example", room("https://matrix.to/#/!room:server.example/\$event:server.example").eventId)
        assertEquals("@user:server.example", user("https://matrix.to/#/@user:server.example"))
    }

    @Test
    fun matrixSchemeSigilLess() {
        assertEquals(
            "#mages:unredacted.org",
            room("matrix:r/mages:unredacted.org?action=join").roomIdOrAlias,
        )
        assertEquals(
            "!BDSybzDpGyDxMHZzpN:unredacted.org",
            room("matrix:roomid/BDSybzDpGyDxMHZzpN:unredacted.org?action=join&via=unredacted.org&via=subraum.link").roomIdOrAlias,
        )
        assertEquals(
            listOf("unredacted.org", "subraum.link"),
            room("matrix:roomid/BDSybzDpGyDxMHZzpN:unredacted.org?action=join&via=unredacted.org&via=subraum.link").via,
        )
        assertEquals("@user:example.org", user("matrix:u/user:example.org?action=chat"))
    }

    @Test
    fun matrixSchemeWithSigils() {
        assertEquals("#mages:unredacted.org", room("matrix:r/#mages:unredacted.org?action=join").roomIdOrAlias)
        assertEquals("!id:server", room("matrix:r/!id:server").roomIdOrAlias)
        assertEquals("@u:server", user("matrix:u/@u:server"))
        assertEquals("@u:server", user("matrix:u/@u%3Aserver"))
        assertEquals("#mages:unredacted.org", room("matrix:r/%23mages%3Aunredacted.org").roomIdOrAlias)
        assertEquals("!id:server", room("matrix:roomid/%21id%3Aserver?via=s.example").roomIdOrAlias)
    }

    @Test
    fun matrixSchemePermalink() {
        assertEquals(
            "\$event",
            room("matrix:roomid/id:server/e/event").eventId,
        )
        assertEquals(
            "\$event",
            room("matrix:roomid/id:server/e/\$event").eventId,
        )
        assertEquals(
            "\$event:server",
            room("matrix:roomid/id:server/\$event:server").eventId,
        )
        assertEquals(
            "\$event:server",
            room("matrix:roomid/id:server/e/event:server").eventId,
        )
    }

    @Test
    fun elementWebInstanceLinks() {
        assertEquals(
            "#mages:unredacted.org",
            room("https://element.unredacted.org/#/room/#mages:unredacted.org?via=unredacted.org&action=join").roomIdOrAlias,
        )
        assertEquals(
            listOf("unredacted.org"),
            room("https://element.unredacted.org/#/room/#mages:unredacted.org?via=unredacted.org&action=join").via,
        )
        assertEquals(
            "!room:server.example",
            room("https://element.unredacted.org/#/room/!room:server.example/\$event:server.example?via=s1.example&action=join").roomIdOrAlias,
        )
        assertEquals(
            "\$event:server.example",
            room("https://element.unredacted.org/#/room/!room:server.example/\$event:server.example?via=s1.example&action=join").eventId,
        )
        assertEquals("@u:server", user("https://app.element.io/#/user/@u:server"))
        assertEquals(
            "#alias:server",
            room("https://app.element.io/#/room/%23alias%3Aserver").roomIdOrAlias,
        )
        // Element instances run on arbitrary hosts, so any #/room/ link whose
        // tail is a matrix identifier is accepted.
        assertEquals(
            "!id:server",
            room("https://example.com/#/room/!id:server").roomIdOrAlias,
        )
    }

    @Test
    fun cinnyLinks() {
        assertEquals("!id:server", room("https://cinny.unredacted.org/home/!id:server").roomIdOrAlias)
        assertEquals("#alias:server", room("https://cinny.unredacted.org/home/%23alias%3Aserver").roomIdOrAlias)
        assertEquals("@user:server", user("https://cinny.unredacted.org/direct/create?userId=@user:server"))
    }

    @Test
    fun elementAppLinks() {
        assertEquals("!id:server", room("element://room/!id:server?via=s.example").roomIdOrAlias)
        assertEquals("#alias:server", room("element://room/%23alias%3Aserver").roomIdOrAlias)
        assertEquals("@u:server", user("element://user/@u:server"))
    }

    @Test
    fun unsupported() {
        for (raw in listOf(
            "",
            "matrix:",
            "matrix:unknown/foo",
            "matrix:r/",
            "matrix:roomid/id/e/",
            "matrix:roomid/id/e/a/b",
            "https://example.com/random",
            "https://matrix.to/#/",
            "matrix.to/#/!id:server",
            "not a link at all",
        )) {
            assertIs<MatrixLink.Unsupported>(parseMatrixLink(raw), "expected unsupported: $raw")
        }
    }

    @Test
    fun invalidViaDropped() {
        assertEquals(
            listOf("good.example"),
            room("matrix:roomid/id:server?via=good.example&via=ugh%3C>nope").via,
        )
    }

    @Test
    fun singlePercentDecode() {
        // %25 decodes to '%' once; a second decode would turn %21 into '!'
        assertEquals(
            "!%21id:server",
            room("matrix:roomid/%2521id%3Aserver").roomIdOrAlias,
        )
        assertEquals(
            "!id:server",
            room("https://matrix.to/#/%21id%3Aserver").roomIdOrAlias,
        )
    }

    @Test
    fun decoratedIdsAreRejected() {
        for (raw in listOf("!id:server?via=x", "#a:b c", "@u:server extra")) {
            assertIs<MatrixLink.Unsupported>(parseMatrixLink(raw), "expected unsupported: $raw")
        }
    }

    @Test
    fun matrixAuthorityForm() {
        assertEquals(
            "#alias:server",
            room("matrix://matrix.example/r/alias:server?via=s.example").roomIdOrAlias,
        )
        assertEquals(
            "#alias:server",
            room("matrix://r/alias:server").roomIdOrAlias,
        )
    }
}

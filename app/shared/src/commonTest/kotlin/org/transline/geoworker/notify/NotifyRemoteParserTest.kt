package org.transline.geoworker.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyRemoteParserTest {

    @Test
    fun parse_requiresIdAndTitle() {
        assertNull(NotifyRemoteParser.parse(mapOf("title" to "T", "body" to "B")))
        assertNull(NotifyRemoteParser.parse(mapOf("id" to "1", "body" to "B")))
        assertNotNull(NotifyRemoteParser.parse(mapOf("id" to "1", "title" to "T", "body" to "B")))
    }

    @Test
    fun parse_actionsJson_andTruncateViaNormalize() {
        val data = mapOf(
            "id" to "n1",
            "title" to "Hello",
            "body" to "World",
            "imageUrl" to "https://example.com/a.png",
            "deepLink" to "app://sbc/1",
            "actions" to """[{"id":"read","title":"R"},{"id":"open","title":"O"},{"id":"close","title":"C"},{"id":"snooze","title":"S"}]""",
            "extraKey" to "extraVal",
        )
        val payload = NotifyRemoteParser.parse(data)!!
        assertEquals(4, payload.actions.size)
        assertEquals("https://example.com/a.png", payload.imageUrl)
        assertEquals("extraVal", payload.data["extraKey"])

        val mgr = NotifyManager(NotifyTestFakeNotifier())
        val normalized = mgr.normalize(payload)!!
        assertEquals(3, normalized.actions.size)
        assertEquals(NotifyActionId.READ, normalized.actions[0].id)
        assertEquals(NotifyActionId.CLOSE, normalized.actions[2].id)
    }

    @Test
    fun parse_actionDeepLink_andResolve() {
        val actions = NotifyRemoteParser.parseActions(
            """[{"id":"read","title":"R","deepLink":"app://sbc/1/read","route":"SbcReader","params":{"mode":"preview"}},{"id":"open","title":"O","deepLink":"app://sbc/1"}]""",
        )
        assertEquals(2, actions.size)
        assertEquals("app://sbc/1/read", actions[0].deepLink)
        assertEquals("SbcReader", actions[0].route)
        assertEquals("preview", actions[0].params["mode"])

        val payload = NotifyPayload(
            id = "n1",
            title = "T",
            body = "B",
            deepLink = "app://sbc/fallback",
            actions = actions,
        )
        assertEquals("app://sbc/1/read", NotifyActionNav.deepLink(NotifyActionId.READ, payload))
        assertEquals("app://sbc/1", NotifyActionNav.deepLink(NotifyActionId.OPEN, payload))
        assertEquals("app://sbc/fallback", NotifyActionNav.deepLink(NotifyActionId.CLOSE, payload))
    }

    @Test
    fun parse_commaActions() {
        val actions = NotifyRemoteParser.parseActions("read,open,close")
        assertEquals(3, actions.size)
        assertEquals(NotifyActionId.OPEN, actions[1].id)
        assertEquals("Перейти", actions[1].title)
    }

    @Test
    fun manager_show_cancel_snooze() {
        val notifier = NotifyTestFakeNotifier()
        val mgr = NotifyManager(notifier)
        assertTrue(
            mgr.show(
                NotifyPayload(
                    id = "x",
                    title = "T",
                    body = "B",
                    actions = listOf(
                        NotifyAction(NotifyActionId.READ, "R"),
                        NotifyAction(NotifyActionId.OPEN, "O"),
                        NotifyAction(NotifyActionId.CLOSE, "C"),
                        NotifyAction(NotifyActionId.SNOOZE, "S"),
                    ),
                )
            )
        )
        assertEquals(1, notifier.shown.size)
        assertEquals(3, notifier.shown[0].actions.size)

        assertFalse(mgr.show(NotifyPayload(id = "", title = "T", body = "B")))
        assertTrue(mgr.cancel("x"))
        assertEquals(listOf("x"), notifier.cancelled)

        mgr.show(NotifyPayload(id = "y", title = "T", body = "B", snoozeMinutes = 5))
        assertTrue(mgr.snooze("y"))
        assertEquals(1, notifier.snoozed.size)
        assertEquals(5, notifier.snoozed[0].second)
    }

    @Test
    fun handleRemote_wiresToShow() {
        val notifier = NotifyTestFakeNotifier()
        val mgr = NotifyManager(notifier)
        assertTrue(
            mgr.handleRemote(
                mapOf("id" to "r1", "title" to "Remote", "body" to "From FCM")
            )
        )
        assertEquals("Remote", notifier.shown.single().title)
    }

    @Test
    fun dispatchAction_closeAndSnooze() {
        val notifier = NotifyTestFakeNotifier()
        val mgr = NotifyManager(notifier)
        val actions = mutableListOf<NotifyActionId>()
        mgr.setActionListener { id, _ -> actions.add(id) }

        mgr.show(
            NotifyPayload(
                id = "z",
                title = "T",
                body = "B",
                snoozeMinutes = 10,
                actions = listOf(NotifyAction(NotifyActionId.SNOOZE, "S")),
            )
        )
        mgr.dispatchAction(NotifyActionId.SNOOZE, "z")
        assertEquals(NotifyActionId.SNOOZE, actions.single())
        assertTrue(notifier.snoozed.isNotEmpty())

        mgr.show(NotifyPayload(id = "c", title = "T", body = "B"))
        mgr.dispatchAction(NotifyActionId.CLOSE, "c")
        assertTrue(notifier.cancelled.contains("c"))
    }
}

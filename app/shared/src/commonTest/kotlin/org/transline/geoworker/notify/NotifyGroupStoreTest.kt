package org.transline.geoworker.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class NotifyTestFakeNotifier : PlatformNotifier {
    val shown = mutableListOf<NotifyPayload>()
    val cancelled = mutableListOf<String>()
    val snoozed = mutableListOf<Pair<NotifyPayload, Int>>()

    override fun show(payload: NotifyPayload): Boolean {
        shown.add(payload)
        return true
    }

    override fun cancel(id: String) {
        cancelled.add(id)
    }

    override fun scheduleSnooze(payload: NotifyPayload, delayMinutes: Int) {
        snoozed.add(payload to delayMinutes)
    }
}

class NotifyGroupStoreTest {

    @Test
    fun deriveFromDeepLink_stripsLastSegment() {
        val pair = NotifyGroupStore.deriveFromDeepLink("app://sbc/42")
        assertNotNull(pair)
        assertEquals("app://sbc", pair.first)
        assertEquals("42", pair.second)
    }

    @Test
    fun deriveFromDeepLink_rejectsHubOnly() {
        assertNull(NotifyGroupStore.deriveFromDeepLink("app://sbc"))
        assertNull(NotifyGroupStore.deriveFromDeepLink("app://sbc/"))
    }

    @Test
    fun add_aggregatesFifoDedupesAndCapsAt15() {
        val store = NotifyGroupStore(maxIds = 15)
        val base = NotifyPayload(
            id = "e0",
            title = "Doc",
            body = "b",
            deepLink = "app://sbc/0",
            actions = listOf(NotifyAction(NotifyActionId.OPEN, "Перейти")),
        )
        // 16 distinct ids → keep last 15
        for (i in 0..15) {
            val agg = store.add(
                base.copy(id = "e$i", deepLink = "app://sbc/$i", entityId = "$i"),
            )
            assertNotNull(agg)
        }
        val state = store.get("app://sbc")!!
        assertEquals(15, state.ids.size)
        assertFalse(state.ids.contains("0"))
        assertTrue(state.ids.contains("1"))
        assertTrue(state.ids.contains("15"))
        assertEquals("15", state.ids.last())
    }

    @Test
    fun add_buildsHubParamsOnOpenAction() {
        val store = NotifyGroupStore()
        val agg = store.add(
            NotifyPayload(
                id = "e1",
                title = "T",
                body = "B",
                deepLink = "app://sbc/7",
                actions = listOf(
                    NotifyAction(NotifyActionId.OPEN, "Список"),
                    NotifyAction(NotifyActionId.CLOSE, "Закрыть"),
                ),
            ),
        )!!
        assertEquals("grp:app://sbc", agg.id)
        assertEquals("app://sbc", agg.deepLink)
        assertEquals("1: 7", agg.body)
        assertEquals("7", agg.data["ids"])
        assertEquals("1", agg.data["count"])
        val open = agg.actions.first { it.id == NotifyActionId.OPEN }
        assertEquals("app://sbc", open.deepLink)
        assertEquals("7", open.params["ids"])
        val close = agg.actions.first { it.id == NotifyActionId.CLOSE }
        assertNull(close.deepLink)
    }

    @Test
    fun manager_show_usesSummaryId() {
        val fake = NotifyTestFakeNotifier()
        val mgr = NotifyManager(fake)
        assertTrue(
            mgr.show(
                NotifyPayload(
                    id = "a",
                    title = "T",
                    body = "B",
                    deepLink = "app://sbc/1",
                    actions = listOf(NotifyAction(NotifyActionId.OPEN, "Go")),
                ),
            ),
        )
        assertTrue(
            mgr.show(
                NotifyPayload(
                    id = "b",
                    title = "T",
                    body = "B",
                    deepLink = "app://sbc/2",
                    actions = listOf(NotifyAction(NotifyActionId.OPEN, "Go")),
                ),
            ),
        )
        assertEquals(2, fake.shown.size)
        assertEquals("grp:app://sbc", fake.shown.last().id)
        assertEquals("2: 1, 2", fake.shown.last().body)
    }

    @Test
    fun parser_readsGroupKeys() {
        val p = NotifyRemoteParser.parse(
            mapOf(
                "id" to "1",
                "title" to "T",
                "groupKey" to "app://sbc",
                "entityId" to "99",
            ),
        )!!
        assertEquals("app://sbc", p.groupKey)
        assertEquals("99", p.entityId)
    }
}

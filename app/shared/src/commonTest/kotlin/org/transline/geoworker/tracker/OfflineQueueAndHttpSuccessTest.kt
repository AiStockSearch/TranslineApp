package org.transline.geoworker.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineQueueAndHttpSuccessTest {

    @Test
    fun isCoordinatesHttpSuccess_accepts2xx() {
        assertTrue(isCoordinatesHttpSuccess(200))
        assertTrue(isCoordinatesHttpSuccess(201))
        assertTrue(isCoordinatesHttpSuccess(204))
        assertFalse(isCoordinatesHttpSuccess(199))
        assertFalse(isCoordinatesHttpSuccess(300))
        assertFalse(isCoordinatesHttpSuccess(401))
    }

    @Test
    fun appendBounded_dropsOldestWhenOverMax() {
        val queue = mutableListOf(1, 2, 3)
        appendBounded(queue, 4, maxSize = 3)
        assertEquals(listOf(2, 3, 4), queue.toList())
        appendBounded(queue, 5, maxSize = 3)
        assertEquals(listOf(3, 4, 5), queue.toList())
    }

    @Test
    fun appendBounded_respectsDefaultMaxConstant() {
        val queue = mutableListOf<Int>()
        repeat(MAX_OFFLINE_QUEUE_SIZE + 10) { i ->
            appendBounded(queue, i)
        }
        assertEquals(MAX_OFFLINE_QUEUE_SIZE, queue.size)
        assertEquals(10, queue.first())
        assertEquals(MAX_OFFLINE_QUEUE_SIZE + 9, queue.last())
    }
}

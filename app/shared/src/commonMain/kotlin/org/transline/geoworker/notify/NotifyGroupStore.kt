package org.transline.geoworker.notify

/**
 * In-memory aggregation of notify payloads by route [groupKey] (max [NotifyManager.MAX_GROUP_IDS] entity ids).
 */
class NotifyGroupStore(
    private val maxIds: Int = NotifyManager.MAX_GROUP_IDS,
) {
    data class GroupState(
        val groupKey: String,
        val ids: List<String>,
        val title: String,
        val body: String,
        val imageUrl: String?,
        val channelId: String?,
        val actions: List<NotifyAction>,
        val snoozeMinutes: Int?,
        val data: Map<String, String>,
    )

    private val groups = linkedMapOf<String, GroupState>()

    fun get(groupKey: String): GroupState? = groups[groupKey]

    fun clear(groupKey: String) {
        groups.remove(groupKey)
    }

    fun clearAll() {
        groups.clear()
    }

    /**
     * Find groupKey that contains [entityId], if any.
     */
    fun findGroupKeyForEntity(entityId: String): String? {
        val id = entityId.trim()
        if (id.isEmpty()) return null
        return groups.entries.firstOrNull { (_, state) -> state.ids.contains(id) }?.key
    }

    /**
     * Remove [entityId] from its group. Returns updated aggregated payload to show,
     * or null if group is empty (caller should cancel summary).
     */
    fun removeEntity(entityId: String): NotifyPayload? {
        val key = findGroupKeyForEntity(entityId) ?: return null
        val state = groups[key] ?: return null
        val nextIds = state.ids.filter { it != entityId.trim() }
        if (nextIds.isEmpty()) {
            groups.remove(key)
            return null
        }
        val updated = state.copy(ids = nextIds)
        groups[key] = updated
        return toAggregatedPayload(updated)
    }

    /**
     * Add payload into its group (if resolvable). Returns aggregated summary payload,
     * or null when aggregation does not apply (caller shows original).
     */
    fun add(payload: NotifyPayload): NotifyPayload? {
        val resolved = resolveGrouping(payload) ?: return null
        val (groupKey, entityId) = resolved

        val existing = groups[groupKey]
        val ids = LinkedHashSet<String>()
        if (existing != null) ids.addAll(existing.ids)
        ids.remove(entityId) // move to end (newest)
        ids.add(entityId)
        while (ids.size > maxIds) {
            val first = ids.first()
            ids.remove(first)
        }

        val groupTitle = payload.data["groupTitle"]?.takeIf { it.isNotBlank() }
            ?: payload.title
        val state = GroupState(
            groupKey = groupKey,
            ids = ids.toList(),
            title = groupTitle,
            body = payload.body,
            imageUrl = payload.imageUrl,
            channelId = payload.channelId,
            actions = payload.actions,
            snoozeMinutes = payload.snoozeMinutes,
            data = payload.data,
        )
        groups[groupKey] = state
        return toAggregatedPayload(state)
    }

    companion object {
        fun summaryNotificationId(groupKey: String): String = "grp:$groupKey"

        fun isSummaryId(id: String): Boolean = id.startsWith("grp:")

        fun groupKeyFromSummaryId(id: String): String? =
            if (isSummaryId(id)) id.removePrefix("grp:") else null

        /**
         * @return pair(groupKey, entityId) or null if cannot aggregate.
         */
        fun resolveGrouping(payload: NotifyPayload): Pair<String, String>? {
            val explicitKey = payload.groupKey?.trim()?.takeIf { it.isNotEmpty() }
            val explicitEntity = payload.entityId?.trim()?.takeIf { it.isNotEmpty() }

            if (explicitKey != null && explicitEntity != null) {
                return explicitKey to explicitEntity
            }

            val deepLink = payload.deepLink?.trim()?.takeIf { it.isNotEmpty() }
            if (deepLink != null) {
                val derived = deriveFromDeepLink(deepLink) ?: return null
                val key = explicitKey ?: derived.first
                val entity = explicitEntity ?: derived.second
                return key to entity
            }

            // route field as hub without path id — need entityId
            val route = payload.data["route"]?.trim()?.takeIf { it.isNotEmpty() }
            if (explicitKey != null && explicitEntity != null) {
                return explicitKey to explicitEntity
            }
            if (route != null && explicitEntity != null) {
                return (explicitKey ?: route) to explicitEntity
            }

            val dataId = payload.data["entityId"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: payload.data["orderId"]?.trim()?.takeIf { it.isNotEmpty() }
            if (explicitKey != null && dataId != null) {
                return explicitKey to dataId
            }
            return null
        }

        /**
         * `app://sbc/42` → (`app://sbc`, `42`); `app://sbc` alone → null.
         */
        fun deriveFromDeepLink(deepLink: String): Pair<String, String>? {
            val trimmed = deepLink.trim().trimEnd('/')
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep < 0) return null
            val afterScheme = trimmed.substring(schemeSep + 3)
            val slash = afterScheme.lastIndexOf('/')
            if (slash <= 0) return null
            val entityId = afterScheme.substring(slash + 1).trim()
            if (entityId.isEmpty() || entityId.contains('?')) {
                val withoutQuery = entityId.substringBefore('?').trim()
                if (withoutQuery.isEmpty()) return null
                val pathBefore = afterScheme.substring(0, slash)
                if (pathBefore.isEmpty()) return null
                val groupKey = trimmed.substring(0, schemeSep + 3) + pathBefore
                return groupKey to withoutQuery
            }
            val pathBefore = afterScheme.substring(0, slash)
            if (pathBefore.isEmpty()) return null
            val groupKey = trimmed.substring(0, schemeSep + 3) + pathBefore
            return groupKey to entityId
        }

        fun toAggregatedPayload(state: GroupState): NotifyPayload {
            val hub = state.groupKey
            val idsJoined = state.ids.joinToString(",")
            val count = state.ids.size.toString()
            val hubParams = mapOf(
                "ids" to idsJoined,
                "count" to count,
                "groupKey" to state.groupKey,
            )
            val actions = state.actions.map { action ->
                when (action.id) {
                    NotifyActionId.OPEN, NotifyActionId.READ -> action.copy(
                        deepLink = hub,
                        params = action.params + hubParams,
                    )
                    else -> action
                }
            }
            val body = "$count: ${state.ids.joinToString(", ")}"
            return NotifyPayload(
                id = summaryNotificationId(state.groupKey),
                title = state.title,
                body = body,
                imageUrl = state.imageUrl,
                deepLink = hub,
                channelId = state.channelId,
                actions = actions,
                data = state.data + hubParams,
                snoozeMinutes = state.snoozeMinutes,
                groupKey = state.groupKey,
                entityId = state.ids.lastOrNull(),
            )
        }
    }
}

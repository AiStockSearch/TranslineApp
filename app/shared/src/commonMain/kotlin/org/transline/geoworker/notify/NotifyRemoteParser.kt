package org.transline.geoworker.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses FCM/APNs `data` map into [NotifyPayload].
 *
 * Expected keys: id, title, body; optional imageUrl, deepLink, channelId, snoozeMinutes,
 * actions (JSON array of {id,title,deepLink?,route?,params?} or comma-separated ids).
 * Remaining string keys go into [NotifyPayload.data].
 */
object NotifyRemoteParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val reserved = setOf(
        "id", "title", "body", "imageUrl", "image_url", "deepLink", "deep_link",
        "channelId", "channel_id", "snoozeMinutes", "snooze_minutes", "actions",
        "type", NotifyManager.OWNER_MARKER_KEY,
        "groupKey", "group_key", "entityId", "entity_id",
    )

    fun parse(data: Map<String, String>): NotifyPayload? {
        val id = data["id"]?.trim().orEmpty()
        val title = data["title"]?.trim().orEmpty()
        val body = data["body"]?.trim().orEmpty()
        if (id.isEmpty() || title.isEmpty()) return null

        val imageUrl = data["imageUrl"] ?: data["image_url"]
        val deepLink = data["deepLink"] ?: data["deep_link"]
        val channelId = data["channelId"] ?: data["channel_id"]
        val snoozeMinutes = (data["snoozeMinutes"] ?: data["snooze_minutes"])?.toIntOrNull()
        val actions = parseActions(data["actions"])
        val groupKey = data["groupKey"] ?: data["group_key"]
        val entityId = data["entityId"] ?: data["entity_id"]

        val extra = data.filterKeys { it !in reserved && !it.equals("image_url", true) }

        return NotifyPayload(
            id = id,
            title = title,
            body = body,
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
            deepLink = deepLink?.takeIf { it.isNotBlank() },
            channelId = channelId?.takeIf { it.isNotBlank() },
            actions = actions,
            data = extra,
            snoozeMinutes = snoozeMinutes,
            groupKey = groupKey?.takeIf { it.isNotBlank() },
            entityId = entityId?.takeIf { it.isNotBlank() },
        )
    }

    fun parseActions(raw: String?): List<NotifyAction> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return runCatching {
                val arr = json.parseToJsonElement(trimmed).jsonArray
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val idRaw = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val actionId = NotifyActionId.fromWire(idRaw) ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: defaultTitle(actionId)
                    val deepLink = obj["deepLink"]?.jsonPrimitive?.contentOrNull
                        ?: obj["deep_link"]?.jsonPrimitive?.contentOrNull
                    val route = obj["route"]?.jsonPrimitive?.contentOrNull
                    val params = parseParamsObject(obj["params"] as? JsonObject)
                    NotifyAction(
                        id = actionId,
                        title = title,
                        deepLink = deepLink?.takeIf { it.isNotBlank() },
                        route = route?.takeIf { it.isNotBlank() },
                        params = params,
                    )
                }
            }.getOrElse { parseCommaActions(trimmed) }
        }
        return parseCommaActions(trimmed)
    }

    private fun parseParamsObject(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.mapNotNull { (k, v) ->
            val content = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
            k to content
        }.toMap()
    }

    private fun parseCommaActions(raw: String): List<NotifyAction> {
        return raw.split(',', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                val actionId = NotifyActionId.fromWire(token) ?: return@mapNotNull null
                NotifyAction(actionId, defaultTitle(actionId))
            }
    }

    fun defaultTitle(id: NotifyActionId): String = when (id) {
        NotifyActionId.READ -> "Прочитать"
        NotifyActionId.OPEN -> "Перейти"
        NotifyActionId.CLOSE -> "Закрыть"
        NotifyActionId.SNOOZE -> "Отложить"
    }

    fun actionsToJson(actions: List<NotifyAction>): String {
        val arr = JsonArray(
            actions.map { a ->
                val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "id" to JsonPrimitive(a.id.name.lowercase()),
                    "title" to JsonPrimitive(a.title),
                )
                a.deepLink?.let { map["deepLink"] = JsonPrimitive(it) }
                a.route?.let { map["route"] = JsonPrimitive(it) }
                if (a.params.isNotEmpty()) {
                    map["params"] = JsonObject(a.params.mapValues { JsonPrimitive(it.value) })
                }
                JsonObject(map)
            }
        )
        return arr.toString()
    }

    fun paramsToJson(params: Map<String, String>): String {
        if (params.isEmpty()) return "{}"
        return JsonObject(params.mapValues { JsonPrimitive(it.value) }).toString()
    }
}

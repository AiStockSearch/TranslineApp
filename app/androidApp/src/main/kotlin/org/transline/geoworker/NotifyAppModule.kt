package org.transline.geoworker

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import org.transline.geoworker.notify.NotifyActionNav
import org.transline.geoworker.notify.NotifyAction
import org.transline.geoworker.notify.NotifyActionId
import org.transline.geoworker.notify.NotifyAndroidContext
import org.transline.geoworker.notify.NotifyEventListener
import org.transline.geoworker.notify.NotifyEventType
import org.transline.geoworker.notify.NotifyManager
import org.transline.geoworker.notify.NotifyManagerHolder
import org.transline.geoworker.notify.NotifyPayload
import org.transline.geoworker.notify.NotifyRemoteParser
import org.transline.geoworker.notify.createPlatformNotifier

/**
 * RN NativeModule [NotifyApp] — thin bridge to KMP [NotifyManager].
 */
class NotifyAppModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "NotifyApp"

    private val manager: NotifyManager by lazy {
        NotifyAndroidContext.init(reactContext)
        NotifyManager(createPlatformNotifier()).also { mgr ->
            NotifyManagerHolder.instance = mgr
            mgr.addEventListener(object : NotifyEventListener {
                override fun onShown(payload: NotifyPayload) {
                    sendEvent(
                        NotifyEventType.SHOWN,
                        Arguments.createMap().apply {
                            putString("id", payload.id)
                            putString("title", payload.title)
                            payload.deepLink?.let { putString("deepLink", it) }
                        },
                    )
                }

                override fun onCancelled(id: String) {
                    sendEvent(
                        NotifyEventType.CANCELLED,
                        Arguments.createMap().apply { putString("id", id) },
                    )
                }

                override fun onAction(actionId: NotifyActionId, payload: NotifyPayload) {
                    val action = NotifyActionNav.actionOf(actionId, payload)
                    sendEvent(
                        NotifyEventType.forAction(actionId),
                        Arguments.createMap().apply {
                            putString("id", payload.id)
                            putString("actionId", actionId.name.lowercase())
                            NotifyActionNav.deepLink(actionId, payload)?.let { putString("deepLink", it) }
                            NotifyActionNav.route(actionId, payload)?.let { putString("route", it) }
                            val params = NotifyActionNav.params(actionId, payload)
                            if (params.isNotEmpty()) {
                                putString("paramsJson", NotifyRemoteParser.paramsToJson(params))
                                putMap(
                                    "params",
                                    Arguments.createMap().apply {
                                        params.forEach { (k, v) -> putString(k, v) }
                                    },
                                )
                            }
                            action?.title?.let { putString("actionTitle", it) }
                        },
                    )
                }
            })
        }
    }

    init {
        // Touch manager so holder + listeners are ready for BroadcastReceiver
        manager
    }

    @ReactMethod
    fun show(json: String, promise: Promise) {
        try {
            val payload = parsePayloadJson(json)
                ?: run {
                    promise.resolve(false)
                    return
                }
            promise.resolve(manager.show(payload))
        } catch (e: Exception) {
            promise.reject("NOTIFY_SHOW_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun handleRemote(data: ReadableMap, promise: Promise) {
        try {
            val map = readableMapToStringMap(data)
            promise.resolve(manager.handleRemote(map))
        } catch (e: Exception) {
            promise.reject("NOTIFY_REMOTE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun cancel(id: String, promise: Promise) {
        promise.resolve(manager.cancel(id))
    }

    @ReactMethod
    fun snooze(id: String, minutes: Int, promise: Promise) {
        val mins = if (minutes > 0) minutes else null
        promise.resolve(manager.snooze(id, mins))
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // RN built-in event emitter requirement
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // RN built-in event emitter requirement
    }

    private fun sendEvent(type: String, payload: WritableMap) {
        payload.putString("type", type)
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(NOTIFY_APP_EVENT, payload)
    }

    companion object {
        const val NOTIFY_APP_EVENT = "onNotifyAppEvent"

        fun parsePayloadJson(json: String): NotifyPayload? {
            val obj = JSONObject(json)
            val id = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) return null
            val body = obj.optString("body", "")
            val imageUrl = stringOrNull(obj, "imageUrl") ?: stringOrNull(obj, "image_url")
            val deepLink = stringOrNull(obj, "deepLink") ?: stringOrNull(obj, "deep_link")
            val channelId = stringOrNull(obj, "channelId")
            val snoozeMinutes = if (obj.has("snoozeMinutes") && !obj.isNull("snoozeMinutes")) {
                obj.optInt("snoozeMinutes")
            } else {
                null
            }
            val actions = mutableListOf<NotifyAction>()
            if (obj.has("actions") && !obj.isNull("actions")) {
                when (val raw = obj.get("actions")) {
                    is JSONArray -> {
                        for (i in 0 until raw.length()) {
                            val a = raw.getJSONObject(i)
                            val actionId = NotifyActionId.fromWire(a.optString("id")) ?: continue
                            val actionTitle = a.optString("title")
                                .ifBlank { NotifyRemoteParser.defaultTitle(actionId) }
                            val actionDeepLink = stringOrNull(a, "deepLink") ?: stringOrNull(a, "deep_link")
                            val actionRoute = stringOrNull(a, "route")
                            val actionParams = mutableMapOf<String, String>()
                            if (a.has("params") && a.get("params") is JSONObject) {
                                val p = a.getJSONObject("params")
                                p.keys().forEach { key -> actionParams[key] = p.optString(key) }
                            }
                            actions.add(
                                NotifyAction(
                                    id = actionId,
                                    title = actionTitle,
                                    deepLink = actionDeepLink,
                                    route = actionRoute,
                                    params = actionParams,
                                )
                            )
                        }
                    }
                    is String -> actions.addAll(NotifyRemoteParser.parseActions(raw))
                }
            }
            val data = mutableMapOf<String, String>()
            if (obj.has("data") && obj.get("data") is JSONObject) {
                val d = obj.getJSONObject("data")
                d.keys().forEach { key -> data[key] = d.optString(key) }
            }
            val groupKey = stringOrNull(obj, "groupKey") ?: stringOrNull(obj, "group_key")
            val entityId = stringOrNull(obj, "entityId") ?: stringOrNull(obj, "entity_id")
            return NotifyPayload(
                id = id,
                title = title,
                body = body,
                imageUrl = imageUrl,
                deepLink = deepLink,
                channelId = channelId,
                actions = actions,
                data = data,
                snoozeMinutes = snoozeMinutes,
                groupKey = groupKey,
                entityId = entityId,
            )
        }

        private fun stringOrNull(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key).takeIf { it.isNotBlank() }
        }

        fun readableMapToStringMap(map: ReadableMap): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val it = map.keySetIterator()
            while (it.hasNextKey()) {
                val key = it.nextKey()
                when (map.getType(key)) {
                    com.facebook.react.bridge.ReadableType.Null -> Unit
                    com.facebook.react.bridge.ReadableType.String ->
                        out[key] = map.getString(key) ?: ""
                    com.facebook.react.bridge.ReadableType.Number ->
                        out[key] = map.getDouble(key).toString()
                    com.facebook.react.bridge.ReadableType.Boolean ->
                        out[key] = map.getBoolean(key).toString()
                    else -> Unit
                }
            }
            return out
        }
    }
}

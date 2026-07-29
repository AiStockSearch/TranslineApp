package org.transline.geoworker.tracker

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    /** GpsService OpenAPI: `speed_mps`; legacy offline queue may still have `speedMps`. */
    @SerialName("speed_mps")
    @JsonNames("speedMps")
    val speedMps: Double,
    val driver_uuid: String
)

interface LocationRepository {
    suspend fun sendOrQueueLocation(location: Location): Boolean
    suspend fun flushOfflineQueue()
}

class DefaultLocationRepository(
    private val httpClient: HttpClient,
    private val storage: TrackingStorage,
    private val networkChecker: NetworkChecker
) : LocationRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sendOrQueueLocation(location: Location): Boolean {
        val endpoint = storage.getApiEndpoint()
        val driverUuid = storage.getDriverUuid()
        val authHeader = storage.getAuthHeader()

        if (endpoint.isNullOrEmpty() || driverUuid.isNullOrEmpty()) {
            return false
        }

        val payload = LocationPayload(
            latitude = location.latitude,
            longitude = location.longitude,
            speedMps = clampSpeedMps(location.speedMps),
            driver_uuid = driverUuid
        )

        if (!networkChecker.isNetworkAvailable()) {
            saveToOfflineQueue(payload)
            return false
        }

        return try {
            val response: HttpResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                if (!authHeader.isNullOrEmpty()) {
                    header("Authorization", authHeader)
                }
                setBody(json.encodeToString(payload))
            }

            if (isCoordinatesHttpSuccess(response.status.value)) {
                true
            } else {
                saveToOfflineQueue(payload)
                false
            }
        } catch (e: Exception) {
            saveToOfflineQueue(payload)
            false
        }
    }

    override suspend fun flushOfflineQueue() {
        if (!networkChecker.isNetworkAvailable()) return

        val queueJson = storage.getOfflineQueueJson() ?: return
        val queue: MutableList<LocationPayload> = try {
            json.decodeFromString(queueJson)
        } catch (e: Exception) {
            mutableListOf()
        }

        if (queue.isEmpty()) return

        val endpoint = storage.getApiEndpoint() ?: return
        val authHeader = storage.getAuthHeader()

        val remainingQueue = mutableListOf<LocationPayload>()

        for (payload in queue) {
            try {
                val response: HttpResponse = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    if (!authHeader.isNullOrEmpty()) {
                        header("Authorization", authHeader)
                    }
                    setBody(json.encodeToString(payload))
                }

                if (!isCoordinatesHttpSuccess(response.status.value)) {
                    remainingQueue.add(payload)
                }
            } catch (e: Exception) {
                remainingQueue.add(payload)
            }
        }

        if (remainingQueue.isEmpty()) {
            storage.setOfflineQueueJson(null)
        } else {
            storage.setOfflineQueueJson(json.encodeToString(remainingQueue))
        }
    }

    private fun saveToOfflineQueue(payload: LocationPayload) {
        val queueJson = storage.getOfflineQueueJson()
        val queue: MutableList<LocationPayload> = try {
            if (queueJson != null) json.decodeFromString(queueJson) else mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }

        appendBounded(queue, payload, MAX_OFFLINE_QUEUE_SIZE)
        storage.setOfflineQueueJson(json.encodeToString(queue))
    }
}
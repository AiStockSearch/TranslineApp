package org.transline.geoworker.tracker

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val driver_uuid: String
)

interface LocationRepository {
    suspend fun sendOrQueueLocation(location: Location): Boolean
    suspend fun flushOfflineQueue()
}

class DefaultLocationRepository(
    private val httpClient: HttpClient,
    private val storage: TrackingStorage
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
            speedMps = location.speedMps,
            driver_uuid = driverUuid
        )

        return try {
            val response: HttpResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                if (!authHeader.isNullOrEmpty()) {
                    header("Authorization", authHeader)
                }
                setBody(json.encodeToString(payload))
            }

            if (response.status.value == 200) {
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

                if (response.status.value != 200) {
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

        queue.add(payload)
        storage.setOfflineQueueJson(json.encodeToString(queue))
    }
}
package org.transline.geoworker.tracker

interface LocationApiService {
    suspend fun sendLocation(location: Location): Boolean
}

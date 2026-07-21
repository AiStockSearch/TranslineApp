package org.transline.geoworker

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
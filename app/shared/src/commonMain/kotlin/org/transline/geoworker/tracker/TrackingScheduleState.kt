package org.transline.geoworker.tracker

data class TrackingScheduleState(
    val lastSentTimestamp: Long?,    // Таймштамп последней успешной отправки (null, если еще не было)
    val nextScheduledTimestamp: Long?, // Таймштамп следующей запланированной отправки
    val isTrackingActive: Boolean    // Активен ли рейс/трекинг вообще
)

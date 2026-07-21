package org.transline.geoworker.tracker

/**
 * In-memory [SecureConfigStore] for unit tests. Does not touch [TrackingStorage].
 */
class InMemorySecureConfigStore : SecureConfigStore {
    private var blob: SecureConfig? = null

    override fun load(): SecureConfig? = blob

    override fun save(config: SecureConfig) {
        blob = config
    }

    override fun clear() {
        blob = null
    }
}

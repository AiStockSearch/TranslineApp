package org.transline.geoworker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.transline.geoworker.tracker.NetworkChecker

class AndroidNetworkChecker(private val context: Context) : NetworkChecker {
    override fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

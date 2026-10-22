package com.hinnka.mycamera.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.Geocoder
import android.location.LocationManager
import android.os.CancellationSignal
import com.hinnka.mycamera.timestamp.TimestampLocationFix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class LocationManager(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var currentLocation: Location? = null

    @SuppressLint("MissingPermission")
    fun requestCurrentLocation() {
        if (!hasLocationPermission()) {
            return
        }

        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                    location?.let { updateCurrentLocation(it) }
                }
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to request current location", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun updateLocation() {
        if (!hasLocationPermission()) {
            return
        }

        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                val location = locationManager.getLastKnownLocation(provider) ?: continue
                updateCurrentLocation(location)
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to get last known location", e)
        }
    }

    fun getCurrentLocation(): Location? {
        updateLocation()
        val location = Location(currentLocation ?: return null)
        if (DeviceUtil.canShowPhantom) {
            val converted = CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
            location.latitude = converted[0]
            location.longitude = converted[1]
        }
        return location
    }

    fun hasLocationPermission(): Boolean {
        val fineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fineLocation || coarseLocation
    }

    @SuppressLint("MissingPermission")
    suspend fun getTimestampLocation(timeoutMillis: Long = 8_000L): TimestampLocationFix? {
        if (!hasLocationPermission()) return null
        val best = CompletableDeferred<Location?>()
        val cancellationSignals = mutableListOf<CancellationSignal>()
        try {
            val providers = locationManager.getProviders(true)
            if (providers.isEmpty()) return null
            providers.forEach { provider ->
                val signal = CancellationSignal().also(cancellationSignals::add)
                runCatching {
                    locationManager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        location?.let(::updateCurrentLocation)
                        if (location != null && !best.isCompleted) best.complete(location)
                    }
                }
            }
            val fresh = withTimeoutOrNull(timeoutMillis) { best.await() }
                ?: getCurrentLocation()
                ?: return null
            val addressLines = reverseGeocode(fresh.latitude, fresh.longitude)
            return TimestampLocationFix(
                latitude = fresh.latitude,
                longitude = fresh.longitude,
                altitude = fresh.altitude.takeIf { fresh.hasAltitude() },
                accuracyMeters = fresh.accuracy.takeIf { fresh.hasAccuracy() } ?: Float.NaN,
                provider = fresh.provider.orEmpty(),
                fixTimeMillis = fresh.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                addressLines = addressLines,
            )
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to resolve timestamp location", e)
            return null
        } finally {
            cancellationSignals.forEach { it.cancel() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(latitude: Double, longitude: Double): List<String> =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext emptyList()
            runCatching {
                val address = Geocoder(context, Locale.getDefault())
                    .getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull() ?: return@runCatching emptyList()
                listOfNotNull(
                    address.thoroughfare?.let { street ->
                        address.subThoroughfare?.takeIf(String::isNotBlank)?.let { "$street $it" } ?: street
                    },
                    address.subLocality,
                    address.locality ?: address.subAdminArea,
                    address.adminArea,
                    address.countryName,
                ).map(String::trim).filter(String::isNotEmpty).distinct()
            }.getOrElse {
                PLog.w(TAG, "Reverse geocoding unavailable", it)
                emptyList()
            }
        }

    private fun updateCurrentLocation(location: Location) {
        val previous = currentLocation
        if (previous == null || isBetterLocation(location, previous)) {
            currentLocation = Location(location)
        }
    }

    private fun isBetterLocation(location: Location, currentBest: Location): Boolean {
        if (location.elapsedRealtimeNanos != currentBest.elapsedRealtimeNanos) {
            return location.elapsedRealtimeNanos > currentBest.elapsedRealtimeNanos
        }
        return location.accuracy < currentBest.accuracy
    }

    companion object {
        private const val TAG = "LocationManager"
    }
}

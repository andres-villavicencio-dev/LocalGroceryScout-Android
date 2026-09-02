package com.localscout.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.localscout.app.domain.model.GeoLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Thin wrapper over Fused Location Provider. Returns null if the user has
 * not granted permission; callers should treat that as "skip location context"
 * (the prompt degrades gracefully without coordinates).
 */
@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission") // guard via hasLocationPermission()
    suspend fun currentLocation(): GeoLocation? {
        if (!hasLocationPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont: CancellableContinuation<GeoLocation?> ->
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    cont.resume(loc?.let { GeoLocation(it.latitude, it.longitude) })
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    /**
     * Reverse-geocode a coordinate into a short human-readable area label for
     * the search screen, e.g. "Ponsonby, Auckland". Uses the platform Geocoder
     * (no API key, no extra dependency). Returns null on any failure — the UI
     * simply hides the address line.
     */
    fun addressLabel(loc: GeoLocation): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            val a = addresses?.firstOrNull() ?: return null
            // Prefer the most specific sane label: suburb → city, falling back
            // through locality/admin area so something useful almost always shows.
            listOfNotNull(a.subLocality, a.locality)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .ifBlank { a.adminArea ?: a.subAdminArea ?: a.countryName }
        } catch (_: Exception) {
            null
        }
    }
}

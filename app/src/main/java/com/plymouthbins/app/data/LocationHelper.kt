package com.plymouthbins.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

object LocationHelper {

    fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun lastKnownPostcode(ctx: Context, onResult: (String?) -> Unit) {
        if (!hasLocationPermission(ctx)) { onResult(null); return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) { onResult(null); return }
        val loc = sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) { onResult(null); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Geocoder(ctx, Locale.UK).getFromLocation(loc.latitude, loc.longitude, 1) { results ->
                onResult(results.firstOrNull()?.postalCode?.replace(" ", ""))
            }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.UK).getFromLocation(loc.latitude, loc.longitude, 1)
            }.onSuccess { results -> onResult(results?.firstOrNull()?.postalCode?.replace(" ", "")) }
                .onFailure { onResult(null) }
        }
    }
}

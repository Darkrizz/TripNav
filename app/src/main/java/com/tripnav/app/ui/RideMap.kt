package com.tripnav.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tripnav.app.offline.MapStyleSpec
import com.tripnav.app.util.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val STYLE_DAY = "https://tiles.openfreemap.org/styles/liberty"
const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

/**
 * MapLibre map showing the route, the travelled part, destination pins and the rider.
 * [follow] keeps the camera locked on the rider in heading-up mode.
 */
@SuppressLint("MissingPermission")
@Composable
fun RideMap(
    modifier: Modifier,
    style: MapStyleSpec,
    location: Location?,
    route: List<LatLon>?,
    traveledToIndex: Int,
    pins: List<LatLon>,
    follow: Boolean,
    fitRoute: Boolean,
    onLongPress: (LatLon) -> Unit,
    onUserPan: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { createMapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val pan by rememberUpdatedState(onUserPan)

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.getMapAsync { m ->
            map = m
            // The map view is rebuilt whenever the Ride tab comes back; start where the rider left it,
            // or on their last fix, instead of the default world view.
            (CameraMemory.last ?: location?.let {
                CameraPosition.Builder().target(LatLng(it.latitude, it.longitude)).zoom(16.0).build()
            })?.let { m.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            m.uiSettings.isCompassEnabled = true
            // Keep the compass clear of the status bar, search box and status pills.
            val dp = mapView.resources.displayMetrics.density
            m.uiSettings.setCompassMargins(0, (190 * dp).toInt(), (16 * dp).toInt(), 0)
            m.uiSettings.compassGravity = android.view.Gravity.TOP or android.view.Gravity.END
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.addOnMapLongClickListener { p -> longPress(LatLon(p.latitude, p.longitude)); true }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = pan()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
        }
        onDispose {
            map?.let { CameraMemory.last = it.cameraPosition }
            lifecycle.removeObserver(obs)
            // Leaving the Ride tab disposes the map while the activity is still resumed; wind the view down in
            // order so MapLibre releases its renderer and location engine instead of leaking them.
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, style.key) {
        val m = map ?: return@LaunchedEffect
        loadedStyle = null
        val builder = style.json?.let { Style.Builder().fromJson(it) } ?: Style.Builder().fromUri(style.uri ?: STYLE_DAY)
        m.setStyle(builder) { s ->
            s.addSource(GeoJsonSource(SRC_ROUTE))
            s.addSource(GeoJsonSource(SRC_DONE))
            s.addSource(GeoJsonSource(SRC_PINS))
            s.addLayer(LineLayer("route-casing", SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#7A2E00")), PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ))
            s.addLayer(LineLayer("route-line", SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#FF7A1A")), PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ))
            s.addLayer(LineLayer("route-done", SRC_DONE).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#8090A0")), PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ))
            s.addLayer(CircleLayer("pins", SRC_PINS).withProperties(
                PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(Color.parseColor("#E5484D")),
                PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f),
            ))
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s).useDefaultLocationEngine(false).build(),
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            loadedStyle = s
        }
    }

    LaunchedEffect(loadedStyle, route, traveledToIndex) {
        val s = loadedStyle ?: return@LaunchedEffect
        val pts = route.orEmpty()
        (s.getSource(SRC_ROUTE) as? GeoJsonSource)?.setGeoJson(line(pts))
        val done = if (pts.size >= 2 && traveledToIndex > 0) pts.subList(0, (traveledToIndex + 1).coerceAtMost(pts.size)) else emptyList()
        (s.getSource(SRC_DONE) as? GeoJsonSource)?.setGeoJson(line(done))
    }

    LaunchedEffect(loadedStyle, pins) {
        val s = loadedStyle ?: return@LaunchedEffect
        (s.getSource(SRC_PINS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(pins.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }),
        )
    }

    LaunchedEffect(loadedStyle, location) {
        val m = map ?: return@LaunchedEffect
        if (loadedStyle == null || location == null) return@LaunchedEffect
        runCatching { m.locationComponent.forceLocationUpdate(location) }
    }

    LaunchedEffect(loadedStyle, follow) {
        val m = map ?: return@LaunchedEffect
        if (loadedStyle == null) return@LaunchedEffect
        runCatching {
            if (follow) {
                m.locationComponent.cameraMode = CameraMode.TRACKING_GPS
                // Tracking only moves the camera on the next fix; push the current one so it snaps now.
                location?.let { m.locationComponent.forceLocationUpdate(it) }
                m.locationComponent.zoomWhileTracking(16.5, 800)
                m.locationComponent.tiltWhileTracking(45.0, 800)
            } else m.locationComponent.cameraMode = CameraMode.NONE
        }
    }

    LaunchedEffect(loadedStyle, fitRoute, route) {
        val m = map ?: return@LaunchedEffect
        val pts = route ?: return@LaunchedEffect
        if (loadedStyle == null || !fitRoute || pts.size < 2) return@LaunchedEffect
        val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        m.locationComponent.cameraMode = CameraMode.NONE
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120, 220, 120, 520), 700)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Last camera position, kept for the life of the process so returning to the Ride tab doesn't reset the map. */
private object CameraMemory { var last: CameraPosition? = null }

private const val SRC_ROUTE = "route"
private const val SRC_DONE = "route-done"
private const val SRC_PINS = "pins"

private fun line(pts: List<LatLon>): FeatureCollection =
    if (pts.size < 2) FeatureCollection.fromFeatures(emptyList())
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })))

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context).apply { onCreate(Bundle()) }
}

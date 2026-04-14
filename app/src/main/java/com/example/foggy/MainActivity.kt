package com.example.foggy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.location.Geocoder
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    private companion object {
        private const val TRACKING_START_ZOOM_LEVEL = 20.0
    }


    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 2
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 3
        private const val POINTS_REFRESH_INTERVAL_MS = 1_000L
        private const val POINT_DIAMETER_METERS = 3.0
        private const val CLEAR_RADIUS_METERS = 15.0
        private const val CLEAR_EDGE_FEATHER_METERS = 6.0
        private const val GRID_CELL_SIZE_METERS = 15.0
        private const val MIN_GRID_CELL_SIZE_PIXELS = 12.0
        private const val SHOW_SAVED_POINTS = false
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/reverse"
        private const val NOMINATIM_USER_AGENT = "Foggy/1.0 (city-boundary feature)"
        private const val WEB_MERCATOR_HALF_WORLD_METERS = 20_037_508.342789244
    }

    private lateinit var map: MapView
    private lateinit var fogModeButton: Button
    private lateinit var trackingButton: Button
    private lateinit var editButton: Button
    private lateinit var editModeHint: TextView
    private lateinit var discoveredPercentText: TextView
    private lateinit var currentCityText: TextView
    private lateinit var locationHistoryDatabase: LocationHistoryDatabase
    private lateinit var savedPointsOverlay: SavedPointsOverlay
    private lateinit var gridOverlay: GridOverlay
    private lateinit var editModeOverlay: EditModeOverlay
    private lateinit var cityBoundaryOverlay: Polygon
    private lateinit var fogOverlay: FogOverlay
    private var locationOverlay: MyLocationNewOverlay? = null
    private var useBlackFog = true
    private var isEditMode = false
    private var hasCenteredOnSavedPoint = false
    private var hasCenteredOnGps = false
    private var lastResolvedCityName: String? = null
    private var lastLoadedCityBoundaryKey: String? = null
    @Volatile private var cityLookupInFlight = false
    private var currentCityBoundary: CityBoundary? = null
    private var currentProjectedCityBoundary: ProjectedBoundary? = null
    private var currentCityBoundaryCellCount: Int? = null
    private val databaseExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cityResolverExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pointsRefreshHandler = Handler(Looper.getMainLooper())
    private val pointsRefresher = object : Runnable {
        override fun run() {
            refreshSavedPoints()
            pointsRefreshHandler.postDelayed(this, POINTS_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )

        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        fogModeButton = findViewById(R.id.fogModeButton)
        trackingButton = findViewById(R.id.trackingButton)
        editButton = findViewById(R.id.editButton)
        editModeHint = findViewById(R.id.editModeHint)
        discoveredPercentText = findViewById(R.id.discoveredPercentText)
        currentCityText = findViewById(R.id.currentCityText)
        locationHistoryDatabase = LocationHistoryDatabase(applicationContext)
        savedPointsOverlay = SavedPointsOverlay()
        gridOverlay = GridOverlay()
        editModeOverlay = EditModeOverlay()
        cityBoundaryOverlay = createCityBoundaryOverlay()
        fogOverlay = FogOverlay()

        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(45.75, 4.85))
        map.overlays.add(savedPointsOverlay)
        map.overlays.add(gridOverlay)
        map.overlays.add(editModeOverlay)
        map.overlays.add(fogOverlay)
        map.overlays.add(cityBoundaryOverlay)
        centerMapOnSavedPointAtStartup()

        trackingButton.setOnClickListener {
            if (LocationTrackingService.isTrackingActive(this)) {
                stopTracking()
            } else {
                startTrackingFlow()
            }
        }

        fogModeButton.setOnClickListener {
            useBlackFog = !useBlackFog
            updateFogModeButton()
            map.invalidate()
        }

        editButton.setOnClickListener {
            isEditMode = !isEditMode
            updateEditModeUi()
        }

        refreshSavedPoints()
        syncTrackingUi()
        updateFogModeButton()
    }

    private fun startTrackingFlow() {
        if (!hasFineLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        startTracking()
    }

    private fun startTracking() {
        LocationTrackingService.updateTrackingState(this, true)
        val serviceIntent = Intent(this, LocationTrackingService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        hasCenteredOnGps = false
        enableLocationOverlay()
        updateTrackingButton()
    }

    private fun stopTracking() {
        LocationTrackingService.updateTrackingState(this, false)
        stopService(Intent(this, LocationTrackingService::class.java))
        disableLocationOverlay()
        updateTrackingButton()
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enableLocationOverlay() {
        if (!hasFineLocationPermission()) return

        val overlay = locationOverlay ?: MyLocationNewOverlay(GpsMyLocationProvider(this), map).also {
            locationOverlay = it
            map.overlays.add(it)
        }

        overlay.enableMyLocation()
        centerMapOnGpsWhenAvailable(overlay)
        updateCurrentCityFromLocation(overlay.myLocation)
        updateDiscoveredPercentage()
        keepOverlayOrder()
        map.invalidate()
    }

    private fun disableLocationOverlay() {
        locationOverlay?.let { overlay ->
            overlay.disableMyLocation()
            map.overlays.remove(overlay)
        }
        locationOverlay = null
        keepOverlayOrder()
        map.invalidate()
    }

    private fun refreshSavedPoints() {
        databaseExecutor.execute {
            val points = locationHistoryDatabase.getAllPoints()
            runOnUiThread {
                savedPointsOverlay.setPoints(points)
                updateCurrentCityFromLocation(locationOverlay?.myLocation)
                updateDiscoveredPercentage()
                map.invalidate()
            }
        }
    }

    private fun centerMapOnSavedPointAtStartup() {
        databaseExecutor.execute {
            val lastPoint = locationHistoryDatabase.getLastPoint()
            runOnUiThread {
                if (lastPoint == null || hasCenteredOnSavedPoint || hasCenteredOnGps) return@runOnUiThread

                map.controller.animateTo(GeoPoint(lastPoint.latitude, lastPoint.longitude))
                hasCenteredOnSavedPoint = true
            }
        }
    }

    private fun centerMapOnGpsWhenAvailable(overlay: MyLocationNewOverlay) {
        if (hasCenteredOnGps) return

        overlay.myLocation?.let { myLocation ->
            centerMapOnGps(myLocation)
            return
        }

        overlay.runOnFirstFix {
            val myLocation = overlay.myLocation ?: return@runOnFirstFix
            runOnUiThread {
                centerMapOnGps(myLocation)
                updateCurrentCityFromLocation(myLocation)
            }
        }
    }

    private fun centerMapOnGps(myLocation: GeoPoint) {
        if (hasCenteredOnGps) return

        map.controller.setZoom(TRACKING_START_ZOOM_LEVEL)
        map.controller.animateTo(myLocation)
        hasCenteredOnGps = true
    }

    private fun updateCurrentCityFromLocation(location: GeoPoint?) {
        if (location == null || !Geocoder.isPresent()) return
        if (cityLookupInFlight) return

        cityLookupInFlight = true
        cityResolverExecutor.execute {
            try {
                val cityName =
                    reverseGeocodeCityName(location.latitude, location.longitude) ?: return@execute
                val boundary = if (cityName != lastLoadedCityBoundaryKey) {
                    fetchCityBoundary(location.latitude, location.longitude)
                } else {
                    null
                }
                val projectedBoundary = boundary?.let(::projectBoundaryToMercator)
                val boundaryCellCount = projectedBoundary?.let(::countCellsInBoundary)

                runOnUiThread {
                    if (cityName != lastResolvedCityName) {
                        lastResolvedCityName = cityName
                        currentCityText.text = cityName
                    }

                    if (boundary != null) {
                        lastLoadedCityBoundaryKey = cityName
                        currentCityBoundary = boundary
                        currentProjectedCityBoundary = projectedBoundary
                        currentCityBoundaryCellCount = boundaryCellCount
                        cityBoundaryOverlay.setPoints(boundary.outerRing)
                        cityBoundaryOverlay.setHoles(boundary.holes)
                        cityBoundaryOverlay.isVisible = true
                        updateDiscoveredPercentage()
                        keepOverlayOrder()
                        map.invalidate()
                    }
                }
            } finally {
                cityLookupInFlight = false
            }
        }
    }

    private fun reverseGeocodeCityName(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val address = geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() ?: return null

            address.locality
                ?: address.subAdminArea
                ?: address.adminArea
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun createCityBoundaryOverlay(): Polygon {
        return Polygon(map).apply {
            outlinePaint.color = Color.argb(255, 255, 214, 10)
            outlinePaint.strokeWidth = 5f
            outlinePaint.style = Paint.Style.STROKE
            fillPaint.color = Color.TRANSPARENT
            isVisible = false
            setOnClickListener { _, _, _ -> true }
        }
    }

    private fun fetchCityBoundary(latitude: Double, longitude: Double): CityBoundary? {
        val url = URL(
            "$NOMINATIM_BASE_URL?format=jsonv2&lat=$latitude&lon=$longitude&zoom=10" +
                "&polygon_geojson=1&polygon_threshold=0.0003"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", NOMINATIM_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        return try {
            if (connection.responseCode !in 200..299) return null

            val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseCityBoundary(JSONObject(response).optJSONObject("geojson") ?: return null)
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCityBoundary(geoJson: JSONObject): CityBoundary? {
        return when (geoJson.optString("type")) {
            "Polygon" -> parsePolygonCoordinates(geoJson.optJSONArray("coordinates") ?: return null)
            "MultiPolygon" -> parseMultiPolygonCoordinates(geoJson.optJSONArray("coordinates") ?: return null)
            else -> null
        }
    }

    private fun parseMultiPolygonCoordinates(coordinates: JSONArray): CityBoundary? {
        var largestBoundary: CityBoundary? = null
        var largestSize = -1

        for (index in 0 until coordinates.length()) {
            val polygonCoordinates = coordinates.optJSONArray(index) ?: continue
            val boundary = parsePolygonCoordinates(polygonCoordinates) ?: continue
            val candidateSize = boundary.outerRing.size

            if (candidateSize > largestSize) {
                largestSize = candidateSize
                largestBoundary = boundary
            }
        }

        return largestBoundary
    }

    private fun parsePolygonCoordinates(coordinates: JSONArray): CityBoundary? {
        if (coordinates.length() == 0) return null

        val outerRing = parseRing(coordinates.optJSONArray(0) ?: return null)
        if (outerRing.isEmpty()) return null

        val holes = mutableListOf<List<GeoPoint>>()
        for (index in 1 until coordinates.length()) {
            val hole = parseRing(coordinates.optJSONArray(index) ?: continue)
            if (hole.isNotEmpty()) {
                holes.add(hole)
            }
        }

        return CityBoundary(
            outerRing = outerRing,
            holes = holes
        )
    }

    private fun parseRing(ringCoordinates: JSONArray): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()

        for (index in 0 until ringCoordinates.length()) {
            val coordinate = ringCoordinates.optJSONArray(index) ?: continue
            if (coordinate.length() < 2) continue

            val longitude = coordinate.optDouble(0, Double.NaN)
            val latitude = coordinate.optDouble(1, Double.NaN)
            if (latitude.isNaN() || longitude.isNaN()) continue

            points.add(GeoPoint(latitude, longitude))
        }

        return points
    }

    private fun updateDiscoveredPercentage() {
        val projectedBoundary = currentProjectedCityBoundary
        val boundaryCellCount = currentCityBoundaryCellCount
        if (projectedBoundary == null || boundaryCellCount == null) {
            discoveredPercentText.text = getString(R.string.discovered_percent_default)
            return
        }

        databaseExecutor.execute {
            val percentage = computeDiscoveredPercentage(
                projectedBoundary,
                boundaryCellCount,
                locationHistoryDatabase.getAllCells()
            )
            val formattedPercentage = String.format(Locale.US, "%.3f%%", percentage)

            runOnUiThread {
                discoveredPercentText.text = formattedPercentage
            }
        }
    }

    private fun computeDiscoveredPercentage(
        projectedBoundary: ProjectedBoundary,
        totalCellsInBoundary: Int,
        discoveredCells: List<LocationHistoryDatabase.StoredGridCell>
    ): Double {
        var discoveredCellsInBoundary = 0

        for (cell in discoveredCells) {
            val cellCenter = ProjectedPoint(
                x = (cell.column + 0.5) * GRID_CELL_SIZE_METERS,
                y = (cell.row + 0.5) * GRID_CELL_SIZE_METERS
            )
            if (isInsideBoundary(cellCenter, projectedBoundary)) {
                discoveredCellsInBoundary += 1
            }
        }

        if (totalCellsInBoundary == 0) return 0.0
        return 100.0 * discoveredCellsInBoundary / totalCellsInBoundary
    }

    private fun projectBoundaryToMercator(boundary: CityBoundary): ProjectedBoundary {
        return ProjectedBoundary(
            outerRing = boundary.outerRing.map {
                ProjectedPoint(
                    x = longitudeToWebMercatorX(it.longitude),
                    y = latitudeToWebMercatorY(it.latitude)
                )
            },
            holes = boundary.holes.map { hole ->
                hole.map {
                    ProjectedPoint(
                        x = longitudeToWebMercatorX(it.longitude),
                        y = latitudeToWebMercatorY(it.latitude)
                    )
                }
            }
        )
    }

    private fun countCellsInBoundary(projectedBoundary: ProjectedBoundary): Int {
        val bounds = computeBounds(projectedBoundary.outerRing) ?: return 0
        val minColumn = kotlin.math.floor(bounds.minX / GRID_CELL_SIZE_METERS).toLong()
        val maxColumn = kotlin.math.ceil(bounds.maxX / GRID_CELL_SIZE_METERS).toLong()
        val minRow = kotlin.math.floor(bounds.minY / GRID_CELL_SIZE_METERS).toLong()
        val maxRow = kotlin.math.ceil(bounds.maxY / GRID_CELL_SIZE_METERS).toLong()
        var totalCellsInBoundary = 0

        for (column in minColumn..maxColumn) {
            val centerX = (column + 0.5) * GRID_CELL_SIZE_METERS
            for (row in minRow..maxRow) {
                val centerY = (row + 0.5) * GRID_CELL_SIZE_METERS
                if (isInsideBoundary(ProjectedPoint(centerX, centerY), projectedBoundary)) {
                    totalCellsInBoundary += 1
                }
            }
        }

        return totalCellsInBoundary
    }

    private fun computeBounds(points: List<ProjectedPoint>): Bounds? {
        if (points.isEmpty()) return null

        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (point in points) {
            minX = minOf(minX, point.x)
            maxX = maxOf(maxX, point.x)
            minY = minOf(minY, point.y)
            maxY = maxOf(maxY, point.y)
        }

        return Bounds(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY
        )
    }

    private fun polygonArea(points: List<ProjectedPoint>): Double {
        if (points.size < 3) return 0.0

        var twiceArea = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            twiceArea += current.x * next.y - next.x * current.y
        }

        return kotlin.math.abs(twiceArea) / 2.0
    }

    private fun isInsideBoundary(
        point: ProjectedPoint,
        boundary: ProjectedBoundary
    ): Boolean {
        if (!isInsidePolygon(point, boundary.outerRing)) return false
        return boundary.holes.none { hole -> isInsidePolygon(point, hole) }
    }

    private fun isInsidePolygon(
        point: ProjectedPoint,
        polygon: List<ProjectedPoint>
    ): Boolean {
        var inside = false
        var previousIndex = polygon.lastIndex

        for (currentIndex in polygon.indices) {
            val current = polygon[currentIndex]
            val previous = polygon[previousIndex]
            val deltaY = previous.y - current.y
            val safeDeltaY = if (deltaY == 0.0) 1e-9 else deltaY
            val intersects =
                ((current.y > point.y) != (previous.y > point.y)) &&
                    (point.x < (previous.x - current.x) * (point.y - current.y) /
                    safeDeltaY + current.x)

            if (intersects) {
                inside = !inside
            }

            previousIndex = currentIndex
        }

        return inside
    }

    private fun longitudeToWebMercatorX(longitude: Double): Double {
        return WEB_MERCATOR_HALF_WORLD_METERS * longitude / 180.0
    }

    private fun latitudeToWebMercatorY(latitude: Double): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        return 6_378_137.0 * kotlin.math.ln(kotlin.math.tan(Math.PI / 4.0 + radians / 2.0))
    }

    private data class CityBoundary(
        val outerRing: List<GeoPoint>,
        val holes: List<List<GeoPoint>>
    )

    private data class ProjectedPoint(
        val x: Double,
        val y: Double
    )

    private data class ProjectedBoundary(
        val outerRing: List<ProjectedPoint>,
        val holes: List<List<ProjectedPoint>>
    )

    private data class Bounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double
    )

    private fun syncTrackingUi() {
        val trackingActive = LocationTrackingService.isTrackingActive(this)
        if (trackingActive && hasFineLocationPermission()) {
            enableLocationOverlay()
        } else {
            disableLocationOverlay()
        }
        keepOverlayOrder()
        updateTrackingButton()
    }

    private fun keepOverlayOrder() {
        map.overlays.remove(gridOverlay)
        map.overlays.remove(editModeOverlay)
        map.overlays.remove(fogOverlay)
        map.overlays.remove(cityBoundaryOverlay)
        map.overlays.add(gridOverlay)
        map.overlays.add(editModeOverlay)
        map.overlays.add(fogOverlay)
        map.overlays.add(cityBoundaryOverlay)
    }

    private fun updateTrackingButton() {
        trackingButton.text = if (LocationTrackingService.isTrackingActive(this)) {
            getString(R.string.stop_tracking)
        } else {
            getString(R.string.start_tracking)
        }
    }

    private fun updateFogModeButton() {
        fogModeButton.text = if (useBlackFog) {
            getString(R.string.show_default_fog)
        } else {
            getString(R.string.show_black_fog)
        }
    }

    private fun updateEditModeUi() {
        editButton.text = if (isEditMode) {
            getString(R.string.edit_mode_exit)
        } else {
            getString(R.string.edit_mode_enter)
        }
        editModeHint.visibility = if (isEditMode) View.VISIBLE else View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            syncTrackingUi()
            return
        }

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE,
            BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE,
            NOTIFICATION_PERMISSION_REQUEST_CODE -> startTrackingFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        syncTrackingUi()
        refreshSavedPoints()
        pointsRefreshHandler.removeCallbacks(pointsRefresher)
        pointsRefreshHandler.postDelayed(pointsRefresher, POINTS_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        pointsRefreshHandler.removeCallbacks(pointsRefresher)
        locationOverlay?.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pointsRefreshHandler.removeCallbacks(pointsRefresher)
        disableLocationOverlay()
        locationHistoryDatabase.close()
        databaseExecutor.shutdown()
        cityResolverExecutor.shutdown()
        super.onDestroy()
    }

    private class SavedPointsOverlay : Overlay() {
        private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        private var points: List<LocationHistoryDatabase.StoredLocationPoint> = emptyList()

        fun setPoints(newPoints: List<LocationHistoryDatabase.StoredLocationPoint>) {
            points = newPoints
        }

        fun getPoints(): List<LocationHistoryDatabase.StoredLocationPoint> = points

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || !SHOW_SAVED_POINTS || points.isEmpty()) return

            val width = mapView.width.toFloat()
            val height = mapView.height.toFloat()

            for (point in points) {
                val geoPoint = GeoPoint(point.latitude, point.longitude)
                val screenPoint = mapView.projection.toPixels(geoPoint, null)
                val radiusPixels = metersToPixels(
                    meters = POINT_DIAMETER_METERS / 2.0,
                    latitude = point.latitude,
                    zoomLevel = mapView.zoomLevelDouble
                )
                val radius = radiusPixels.toFloat().coerceAtLeast(2f)

                if (screenPoint.x + radius < 0f ||
                    screenPoint.x - radius > width ||
                    screenPoint.y + radius < 0f ||
                    screenPoint.y - radius > height
                ) {
                    continue
                }

                canvas.drawCircle(
                    screenPoint.x.toFloat(),
                    screenPoint.y.toFloat(),
                    radius,
                    pointPaint
                )
            }
        }

        private fun metersToPixels(meters: Double, latitude: Double, zoomLevel: Double): Double {
            val metersPerPixel =
                156543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoomLevel)
            return if (metersPerPixel > 0.0) meters / metersPerPixel else meters
        }
    }

    private inner class FogOverlay : Overlay() {
        private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return

            fogPaint.color = if (useBlackFog) {
                Color.argb(255, 0, 0, 0)
            } else {
                Color.argb(210, 215, 220, 225)
            }

            val layerId = canvas.saveLayer(
                0f,
                0f,
                mapView.width.toFloat(),
                mapView.height.toFloat(),
                null
            )

            canvas.drawRect(
                0f,
                0f,
                mapView.width.toFloat(),
                mapView.height.toFloat(),
                fogPaint
            )

            drawRevealedGridCells(canvas, mapView)

            canvas.restoreToCount(layerId)
        }

        private fun drawRevealedGridCells(canvas: Canvas, mapView: MapView) {
            clearPaint.shader = null
            val screenWidth = mapView.width.toFloat()
            val screenHeight = mapView.height.toFloat()
            val visibleBounds = visibleBounds(mapView)

            for (point in savedPointsOverlay.getPoints()) {
                if (!visibleBounds.contains(point.latitude, point.longitude)) {
                    continue
                }

                val pointX = longitudeToWebMercatorX(point.longitude)
                val pointY = latitudeToWebMercatorY(point.latitude)
                val column = kotlin.math.floor(pointX / GRID_CELL_SIZE_METERS).toLong()
                val row = kotlin.math.floor(pointY / GRID_CELL_SIZE_METERS).toLong()
                val left = column * GRID_CELL_SIZE_METERS
                val right = left + GRID_CELL_SIZE_METERS
                val top = row * GRID_CELL_SIZE_METERS
                val bottom = top + GRID_CELL_SIZE_METERS

                val topLeft = mapView.projection.toPixels(
                    GeoPoint(webMercatorYToLatitude(top), webMercatorXToLongitude(left)),
                    null
                )
                val bottomRight = mapView.projection.toPixels(
                    GeoPoint(webMercatorYToLatitude(bottom), webMercatorXToLongitude(right)),
                    null
                )

                val rectLeft = minOf(topLeft.x, bottomRight.x).toFloat()
                val rectRight = maxOf(topLeft.x, bottomRight.x).toFloat()
                val rectTop = minOf(topLeft.y, bottomRight.y).toFloat()
                val rectBottom = maxOf(topLeft.y, bottomRight.y).toFloat()

                if (rectRight < 0f || rectLeft > screenWidth || rectBottom < 0f || rectTop > screenHeight) {
                    continue
                }

                canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, clearPaint)
            }
        }

        private fun visibleBounds(mapView: MapView): VisibleBounds {
            val boundingBox = mapView.boundingBox
            val centerLatitude = (boundingBox.latNorth + boundingBox.latSouth) / 2.0
            val latitudeMargin = GRID_CELL_SIZE_METERS / 110_540.0
            val longitudeMargin =
                GRID_CELL_SIZE_METERS /
                    (111_320.0 * cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.01))

            return VisibleBounds(
                north = (boundingBox.latNorth + latitudeMargin).coerceAtMost(85.05112878),
                south = (boundingBox.latSouth - latitudeMargin).coerceAtLeast(-85.05112878),
                west = boundingBox.lonWest - longitudeMargin,
                east = boundingBox.lonEast + longitudeMargin
            )
        }

        private fun longitudeToWebMercatorX(longitude: Double): Double {
            return WEB_MERCATOR_HALF_WORLD_METERS * longitude / 180.0
        }

        private fun latitudeToWebMercatorY(latitude: Double): Double {
            val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
            return 6_378_137.0 * kotlin.math.ln(kotlin.math.tan(Math.PI / 4.0 + radians / 2.0))
        }

        private fun webMercatorXToLongitude(x: Double): Double {
            return (x / WEB_MERCATOR_HALF_WORLD_METERS) * 180.0
        }

        private fun webMercatorYToLatitude(y: Double): Double {
            val radians = 2.0 * kotlin.math.atan(kotlin.math.exp(y / 6_378_137.0)) - Math.PI / 2.0
            return Math.toDegrees(radians)
        }
    }

    private inner class GridOverlay : Overlay() {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 214, 10)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return

            val center = mapView.mapCenter as? GeoPoint ?: return
            val cellSizePixels = metersToPixels(
                meters = GRID_CELL_SIZE_METERS,
                latitude = center.latitude,
                zoomLevel = mapView.zoomLevelDouble
            )
            if (cellSizePixels < MIN_GRID_CELL_SIZE_PIXELS) return

            val boundingBox = mapView.boundingBox
            val north = boundingBox.latNorth.coerceIn(-85.05112878, 85.05112878)
            val south = boundingBox.latSouth.coerceIn(-85.05112878, 85.05112878)
            val west = boundingBox.lonWest
            val east = boundingBox.lonEast

            val minX = longitudeToWebMercatorX(west)
            val maxX = longitudeToWebMercatorX(east)
            val minY = latitudeToWebMercatorY(south)
            val maxY = latitudeToWebMercatorY(north)

            val startColumn = kotlin.math.floor(minX / GRID_CELL_SIZE_METERS).toLong()
            val endColumn = kotlin.math.ceil(maxX / GRID_CELL_SIZE_METERS).toLong()
            val startRow = kotlin.math.floor(minY / GRID_CELL_SIZE_METERS).toLong()
            val endRow = kotlin.math.ceil(maxY / GRID_CELL_SIZE_METERS).toLong()

            val path = Path()

            for (column in startColumn..endColumn) {
                val x = column * GRID_CELL_SIZE_METERS
                val startPoint = GeoPoint(
                    webMercatorYToLatitude(minY),
                    webMercatorXToLongitude(x)
                )
                val endPoint = GeoPoint(
                    webMercatorYToLatitude(maxY),
                    webMercatorXToLongitude(x)
                )
                val startPixels = mapView.projection.toPixels(startPoint, null)
                val endPixels = mapView.projection.toPixels(endPoint, null)
                path.moveTo(startPixels.x.toFloat(), startPixels.y.toFloat())
                path.lineTo(endPixels.x.toFloat(), endPixels.y.toFloat())
            }

            for (row in startRow..endRow) {
                val y = row * GRID_CELL_SIZE_METERS
                val startPoint = GeoPoint(
                    webMercatorYToLatitude(y),
                    webMercatorXToLongitude(minX)
                )
                val endPoint = GeoPoint(
                    webMercatorYToLatitude(y),
                    webMercatorXToLongitude(maxX)
                )
                val startPixels = mapView.projection.toPixels(startPoint, null)
                val endPixels = mapView.projection.toPixels(endPoint, null)
                path.moveTo(startPixels.x.toFloat(), startPixels.y.toFloat())
                path.lineTo(endPixels.x.toFloat(), endPixels.y.toFloat())
            }

            canvas.drawPath(path, linePaint)
        }

        private fun longitudeToWebMercatorX(longitude: Double): Double {
            return WEB_MERCATOR_HALF_WORLD_METERS * longitude / 180.0
        }

        private fun latitudeToWebMercatorY(latitude: Double): Double {
            val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
            return 6_378_137.0 * kotlin.math.ln(kotlin.math.tan(Math.PI / 4.0 + radians / 2.0))
        }

        private fun webMercatorXToLongitude(x: Double): Double {
            return (x / WEB_MERCATOR_HALF_WORLD_METERS) * 180.0
        }

        private fun webMercatorYToLatitude(y: Double): Double {
            val radians = 2.0 * kotlin.math.atan(kotlin.math.exp(y / 6_378_137.0)) - Math.PI / 2.0
            return Math.toDegrees(radians)
        }

        private fun metersToPixels(meters: Double, latitude: Double, zoomLevel: Double): Double {
            val metersPerPixel =
                156543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoomLevel)
            return if (metersPerPixel > 0.0) meters / metersPerPixel else meters
        }
    }

    private data class VisibleBounds(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double
    ) {
        fun contains(latitude: Double, longitude: Double): Boolean {
            return latitude in south..north && longitude in west..east
        }
    }

    private inner class EditModeOverlay : Overlay() {
        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            if (!isEditMode) return false
            val geoPoint = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            databaseExecutor.execute {
                locationHistoryDatabase.insertManualPoint(geoPoint.latitude, geoPoint.longitude)
                runOnUiThread { refreshSavedPoints() }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
            if (!isEditMode) return false
            val geoPoint = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
            databaseExecutor.execute {
                locationHistoryDatabase.deleteNearPoint(geoPoint.latitude, geoPoint.longitude)
                runOnUiThread { refreshSavedPoints() }
            }
            return true
        }
    }
}

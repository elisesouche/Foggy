package com.example.foggy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

class LocationHistoryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    data class StoredGridCell(
        val column: Long,
        val row: Long
    )

    data class StoredLocationPoint(
        val latitude: Double,
        val longitude: Double
    )

    override fun onCreate(db: SQLiteDatabase) {
        createGridCellsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion

        while (version < newVersion) {
            when (version) {
                1 -> {
                    migrateLegacyGpsPointsToSingleCells(db)
                    version = 2
                }

                2 -> {
                    migrateSingleCellsToRevealedCells(db)
                    version = 3
                }

                else -> {
                    db.execSQL("DROP TABLE IF EXISTS $TABLE_POINTS")
                    onCreate(db)
                    return
                }
            }
        }
    }

    fun insertPoint(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        recordedAt: Long
    ) {
        val ignoredAltitude = altitude
        val ignoredAccuracy = accuracy
        if (ignoredAltitude.isNaN() || ignoredAccuracy.isNaN()) {
            return
        }

        val cells = revealedCellsAround(latitude, longitude)
        writableDatabase.beginTransaction()
        try {
            for (cell in cells) {
                insertGridCell(
                    db = writableDatabase,
                    tableName = TABLE_POINTS,
                    cell = cell,
                    recordedAt = recordedAt
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun insertManualPoint(latitude: Double, longitude: Double) {
        insertPoint(
            latitude = latitude,
            longitude = longitude,
            altitude = 0.0,
            accuracy = 0f,
            recordedAt = System.currentTimeMillis()
        )
    }

    fun deleteNearPoint(latitude: Double, longitude: Double) {
        val cells = revealedCellsAround(latitude, longitude)
        writableDatabase.beginTransaction()
        try {
            for (cell in cells) {
                writableDatabase.delete(
                    TABLE_POINTS,
                    "$COLUMN_GRID_COLUMN = ? AND $COLUMN_GRID_ROW = ?",
                    arrayOf(cell.column.toString(), cell.row.toString())
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getAllPoints(): List<StoredLocationPoint> {
        return getAllCells().map(::gridCellToLatLon)
    }

    fun getAllCells(): List<StoredGridCell> {
        val cells = mutableListOf<StoredGridCell>()
        val query = """
            SELECT $COLUMN_GRID_COLUMN, $COLUMN_GRID_ROW
            FROM $TABLE_POINTS
            ORDER BY $COLUMN_RECORDED_AT ASC
        """.trimIndent()

        readableDatabase.rawQuery(query, null).use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(COLUMN_GRID_COLUMN)
            val rowIndex = cursor.getColumnIndexOrThrow(COLUMN_GRID_ROW)

            while (cursor.moveToNext()) {
                cells.add(
                    StoredGridCell(
                        column = cursor.getLong(columnIndex),
                        row = cursor.getLong(rowIndex)
                    )
                )
            }
        }

        return cells
    }

    fun getLastPoint(): StoredLocationPoint? {
        val query = """
            SELECT $COLUMN_GRID_COLUMN, $COLUMN_GRID_ROW
            FROM $TABLE_POINTS
            ORDER BY $COLUMN_RECORDED_AT DESC
            LIMIT 1
        """.trimIndent()

        readableDatabase.rawQuery(query, null).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val columnIndex = cursor.getColumnIndexOrThrow(COLUMN_GRID_COLUMN)
            val rowIndex = cursor.getColumnIndexOrThrow(COLUMN_GRID_ROW)
            return gridCellToLatLon(
                StoredGridCell(
                    column = cursor.getLong(columnIndex),
                    row = cursor.getLong(rowIndex)
                )
            )
        }
    }

    private fun createGridCellsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_POINTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_GRID_COLUMN INTEGER NOT NULL,
                $COLUMN_GRID_ROW INTEGER NOT NULL,
                $COLUMN_RECORDED_AT INTEGER NOT NULL,
                UNIQUE($COLUMN_GRID_COLUMN, $COLUMN_GRID_ROW)
            )
            """.trimIndent()
        )
    }

    private fun migrateLegacyGpsPointsToSingleCells(db: SQLiteDatabase) {
        recreateAsGridCells(db)

        db.rawQuery(
            """
            SELECT $COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_RECORDED_AT
            FROM ${TABLE_POINTS}_legacy
            ORDER BY $COLUMN_RECORDED_AT ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            val latitudeIndex = cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)
            val longitudeIndex = cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE)
            val recordedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_RECORDED_AT)

            while (cursor.moveToNext()) {
                insertGridCell(
                    db = db,
                    tableName = TABLE_POINTS,
                    cell = latLonToGridCell(
                        latitude = cursor.getDouble(latitudeIndex),
                        longitude = cursor.getDouble(longitudeIndex)
                    ),
                    recordedAt = cursor.getLong(recordedAtIndex)
                )
            }
        }

        db.execSQL("DROP TABLE IF EXISTS ${TABLE_POINTS}_legacy")
    }

    private fun migrateSingleCellsToRevealedCells(db: SQLiteDatabase) {
        recreateAsGridCells(db)

        db.rawQuery(
            """
            SELECT $COLUMN_GRID_COLUMN, $COLUMN_GRID_ROW, $COLUMN_RECORDED_AT
            FROM ${TABLE_POINTS}_legacy
            ORDER BY $COLUMN_RECORDED_AT ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(COLUMN_GRID_COLUMN)
            val rowIndex = cursor.getColumnIndexOrThrow(COLUMN_GRID_ROW)
            val recordedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_RECORDED_AT)

            while (cursor.moveToNext()) {
                val sourceCell = StoredGridCell(
                    column = cursor.getLong(columnIndex),
                    row = cursor.getLong(rowIndex)
                )
                val center = gridCellToLatLon(sourceCell)
                val recordedAt = cursor.getLong(recordedAtIndex)

                for (cell in revealedCellsAround(center.latitude, center.longitude)) {
                    insertGridCell(db, TABLE_POINTS, cell, recordedAt)
                }
            }
        }

        db.execSQL("DROP TABLE IF EXISTS ${TABLE_POINTS}_legacy")
    }

    private fun recreateAsGridCells(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE $TABLE_POINTS RENAME TO ${TABLE_POINTS}_legacy")
            createGridCellsTable(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertGridCell(
        db: SQLiteDatabase,
        tableName: String,
        cell: StoredGridCell,
        recordedAt: Long
    ) {
        val values = ContentValues().apply {
            put(COLUMN_GRID_COLUMN, cell.column)
            put(COLUMN_GRID_ROW, cell.row)
            put(COLUMN_RECORDED_AT, recordedAt)
        }

        db.insertWithOnConflict(
            tableName,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun revealedCellsAround(latitude: Double, longitude: Double): Set<StoredGridCell> {
        val pointX = longitudeToWebMercatorX(longitude)
        val pointY = latitudeToWebMercatorY(latitude)
        val minColumn = floor((pointX - REVEAL_RADIUS_METERS) / GRID_CELL_SIZE_METERS).toLong()
        val maxColumn = ceil((pointX + REVEAL_RADIUS_METERS) / GRID_CELL_SIZE_METERS).toLong()
        val minRow = floor((pointY - REVEAL_RADIUS_METERS) / GRID_CELL_SIZE_METERS).toLong()
        val maxRow = ceil((pointY + REVEAL_RADIUS_METERS) / GRID_CELL_SIZE_METERS).toLong()
        val revealedCells = mutableSetOf<StoredGridCell>()

        for (column in minColumn..maxColumn) {
            val cellLeft = column * GRID_CELL_SIZE_METERS
            val cellRight = cellLeft + GRID_CELL_SIZE_METERS

            for (row in minRow..maxRow) {
                val cellTop = row * GRID_CELL_SIZE_METERS
                val cellBottom = cellTop + GRID_CELL_SIZE_METERS
                val dx = when {
                    pointX < cellLeft -> cellLeft - pointX
                    pointX > cellRight -> pointX - cellRight
                    else -> 0.0
                }
                val dy = when {
                    pointY < cellTop -> cellTop - pointY
                    pointY > cellBottom -> pointY - cellBottom
                    else -> 0.0
                }

                if (dx * dx + dy * dy <= REVEAL_RADIUS_METERS * REVEAL_RADIUS_METERS) {
                    revealedCells.add(StoredGridCell(column = column, row = row))
                }
            }
        }

        return revealedCells
    }

    private fun latLonToGridCell(latitude: Double, longitude: Double): StoredGridCell {
        val x = longitudeToWebMercatorX(longitude)
        val y = latitudeToWebMercatorY(latitude)
        return StoredGridCell(
            column = floor(x / GRID_CELL_SIZE_METERS).toLong(),
            row = floor(y / GRID_CELL_SIZE_METERS).toLong()
        )
    }

    private fun gridCellToLatLon(cell: StoredGridCell): StoredLocationPoint {
        val centerX = (cell.column + 0.5) * GRID_CELL_SIZE_METERS
        val centerY = (cell.row + 0.5) * GRID_CELL_SIZE_METERS
        return StoredLocationPoint(
            latitude = webMercatorYToLatitude(centerY),
            longitude = webMercatorXToLongitude(centerX)
        )
    }

    private fun longitudeToWebMercatorX(longitude: Double): Double {
        return WEB_MERCATOR_HALF_WORLD_METERS * longitude / 180.0
    }

    private fun latitudeToWebMercatorY(latitude: Double): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        return 6_378_137.0 * ln(tan(Math.PI / 4.0 + radians / 2.0))
    }

    private fun webMercatorXToLongitude(x: Double): Double {
        return (x / WEB_MERCATOR_HALF_WORLD_METERS) * 180.0
    }

    private fun webMercatorYToLatitude(y: Double): Double {
        val radians = 2.0 * atan(exp(y / 6_378_137.0)) - Math.PI / 2.0
        return Math.toDegrees(radians)
    }

    companion object {
        private const val DATABASE_NAME = "location_history.db"
        private const val DATABASE_VERSION = 3
        private const val GRID_CELL_SIZE_METERS = 15.0
        private const val REVEAL_RADIUS_METERS = 15.0
        private const val WEB_MERCATOR_HALF_WORLD_METERS = 20_037_508.342789244

        const val TABLE_POINTS = "gps_points"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
        const val COLUMN_RECORDED_AT = "recorded_at"
        const val COLUMN_GRID_COLUMN = "grid_column"
        const val COLUMN_GRID_ROW = "grid_row"
    }
}

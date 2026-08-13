package com.skywell.skydash.trip

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.skywell.skydash.data.SkyDashDatabase
import com.skywell.skydash.data.VehicleSnapshot

class BlackBoxRecorder(private val context: Context) {
    private val TAG = "BlackBoxRecorder"
    private val dbHelper = SkyDashDatabase(context)
    private var currentTripId: Long = -1L
    private var tripStartTime: Long = 0L
    private var lastRecordTime: Long = 0L

    // For mock simulations of missing HAL sensors (altitude, motor temp, cell diff)
    private var simulatedAltitude = 100.0f
    private var altitudeDirection = 1.0f

    @SuppressLint("MissingPermission")
    private fun getGpsAltitude(): Double? {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val providers = lm.getProviders(true)
                for (provider in providers) {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && loc.hasAltitude()) {
                        return loc.altitude
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore permission or service issues
        }
        return null
    }

    fun onSnapshot(snapshot: VehicleSnapshot) {
        val now = System.currentTimeMillis()
        val isIgnitionOn = snapshot.ignitionState >= 2 // 2: ON, 3: START

        if (isIgnitionOn) {
            if (currentTripId == -1L) {
                // 1. Ignition turned ON -> Start a new trip
                startNewTrip(now)
            }

            // 2. Record telemetry snapshot every 5 seconds
            if (now - lastRecordTime >= 5000L) {
                recordTelemetry(snapshot, now)
                lastRecordTime = now
            }
        } else {
            if (currentTripId != -1L) {
                // 3. Ignition turned OFF -> Close current trip
                closeCurrentTrip(now, snapshot)
            }
        }
    }

    private fun startNewTrip(startTime: Long) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("start_time", startTime)
            put("end_time", 0)
            put("distance", 0.0)
            put("duration", 0)
            put("energy_used", 0.0)
            put("avg_consumption", 0.0)
            put("avg_speed", 0.0)
            put("cost", 0.0)
        }
        currentTripId = db.insert("trips", null, values)
        tripStartTime = startTime
        lastRecordTime = startTime
        simulatedAltitude = 100.0f // Reset simulation
        Log.i(TAG, "Started new Black Box trip session: $currentTripId")
    }

    private fun recordTelemetry(snapshot: VehicleSnapshot, timestamp: Long) {
        if (currentTripId == -1L) return

        val db = dbHelper.writableDatabase
        
        // Retrieve actual GPS altitude or run simulation if not available
        val gpsAlt = getGpsAltitude()
        val altitude = if (gpsAlt != null) {
            gpsAlt.toFloat()
        } else {
            // Simulate realistic altitude changes (+/- 1m per tick depending on speed)
            if (snapshot.speed > 5.0f) {
                if (Math.random() > 0.85) altitudeDirection *= -1
                simulatedAltitude += (snapshot.speed * 0.1f * altitudeDirection)
                if (simulatedAltitude < 10) simulatedAltitude = 10f
                if (simulatedAltitude > 1500) simulatedAltitude = 1500f
            }
            simulatedAltitude
        }

        // Simulate realistic motor temperature based on speed and time
        // Typical range 40C - 85C
        val motorTemp = 40.0f + (snapshot.speed * 0.3f) + ((timestamp - tripStartTime) / 60000.0f * 0.5f)
        val finalMotorTemp = Math.min(motorTemp, 85.0f)

        // Simulate cell voltage difference (mV) based on speed (current draw)
        // High acceleration = high current draw = higher voltage difference
        val cellDiffMv = 10 + (snapshot.speed * 0.15f).toInt() + (Math.random() * 3).toInt()

        val relativeTimeSec = ((timestamp - tripStartTime) / 1000L).toInt()

        val values = ContentValues().apply {
            put("trip_id", currentTripId)
            put("timestamp", relativeTimeSec)
            put("soc", snapshot.soc)
            put("speed", snapshot.speed)
            put("altitude", altitude)
            put("motor_temp", finalMotorTemp)
            put("cell_diff_mv", cellDiffMv)
        }
        db.insert("telemetry", null, values)
        Log.d(TAG, "Recorded telemetry for trip $currentTripId, relative time: $relativeTimeSec s")
    }

    private fun closeCurrentTrip(endTime: Long, lastSnapshot: VehicleSnapshot) {
        if (currentTripId == -1L) return

        val db = dbHelper.writableDatabase
        val durationSec = ((endTime - tripStartTime) / 1000L).toInt()

        // Calculate summary statistics from telemetry database records
        var avgSpeed = 0.0f
        var maxSoc = lastSnapshot.soc
        var minSoc = lastSnapshot.soc

        val cursor = db.rawQuery(
            "SELECT AVG(speed), MAX(soc), MIN(soc) FROM telemetry WHERE trip_id = ?",
            arrayOf(currentTripId.toString())
        )
        if (cursor.moveToFirst()) {
            avgSpeed = cursor.getFloat(0)
            maxSoc = cursor.getInt(1)
            minSoc = cursor.getInt(2)
        }
        cursor.close()

        val socDelta = if (maxSoc > minSoc) maxSoc - minSoc else 0
        // Standard battery capacity is 68 kWh
        val energyUsed = (socDelta / 100.0f) * 68.0f 
        val distance = if (lastSnapshot.odometer > 0) {
            // Find start Odometer of this trip
            var startOdo = lastSnapshot.odometer
            val odoCursor = db.rawQuery("SELECT MIN(odometer) FROM (SELECT speed as odometer FROM telemetry WHERE trip_id = ?)", arrayOf(currentTripId.toString())) // Just placeholder or we query TripManager
            // Let's use custom summary calculations
            cursor.close()
            energyUsed // placeholder
        } else {
            0.0f
        }

        // Calculate cost based on AC charger default
        val settingsPrefs = context.getSharedPreferences("SkyDashSettings", Context.MODE_PRIVATE)
        val acPrice = settingsPrefs.getFloat("ac_price", 2.20f)
        val cost = energyUsed * acPrice
        val avgConsumption = if (distance > 0.5) (energyUsed / distance) * 100f else 0.0f

        val values = ContentValues().apply {
            put("end_time", endTime)
            put("duration", durationSec)
            put("avg_speed", avgSpeed)
            put("energy_used", energyUsed)
            put("cost", cost)
            // If actual distance from TripManager is available, we will set it during service update
        }
        db.update("trips", values, "id = ?", arrayOf(currentTripId.toString()))
        Log.i(TAG, "Closed Black Box trip session: $currentTripId, Duration: $durationSec s")
        currentTripId = -1L
    }

    // Setter to update final trip distance from the more precise TripManager
    fun updateFinalTripStats(distance: Float, energyUsed: Float, cost: Float) {
        if (currentTripId == -1L) {
            // Update the last completed trip
            val db = dbHelper.writableDatabase
            val cursor = db.rawQuery("SELECT MAX(id) FROM trips", null)
            if (cursor.moveToFirst()) {
                val lastId = cursor.getLong(0)
                if (lastId > 0) {
                    val values = ContentValues().apply {
                        put("distance", distance)
                        put("energy_used", energyUsed)
                        put("cost", cost)
                        if (distance > 0.1f) {
                            put("avg_consumption", (energyUsed / distance) * 100f)
                        }
                    }
                    db.update("trips", values, "id = ?", arrayOf(lastId.toString()))
                    Log.i(TAG, "Updated last completed trip $lastId stats from TripManager: dist=$distance, energy=$energyUsed")
                }
            }
            cursor.close()
        }
    }
}

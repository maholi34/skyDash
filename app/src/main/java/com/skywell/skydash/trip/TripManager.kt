package com.skywell.skydash.trip

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.skywell.skydash.data.VehicleSnapshot

class TripManager(private val context: Context) {
    
    data class TripData(
        val id: String, // "A", "B", "C"
        var startOdo: Float = -1f,
        var currentOdo: Float = -1f,
        var startEnergy: Float = -1f,
        var currentEnergy: Float = -1f,
        var startSoc: Int = -1,
        var currentSoc: Int = -1,
        var startTime: Long = 0L,
        var activeDuration: Long = 0L, // in seconds
        var parkDuration: Long = 0L,   // in seconds
        var lastUpdateTime: Long = 0L,
        var speedSum: Float = 0f,
        var speedSamples: Int = 0,
        var klimaEnergy: Float = 0f,
        var isKlimaActive: Boolean = false,
        var lastKlimaTime: Long = 0L
    ) {
        val distance: Float
            get() = if (startOdo >= 0 && currentOdo >= startOdo) currentOdo - startOdo else 0.0f

        val energyUsed: Float
            get() = if (startEnergy >= 0 && currentEnergy >= startEnergy) currentEnergy - startEnergy else 0.0f

        val socDeltaPercent: Float
            get() = if (startSoc >= 0 && currentSoc >= 0) (startSoc - currentSoc).toFloat() else 0.0f

        val avgConsumption: Float
            get() {
                val dist = distance
                return if (dist > 0.1f) (energyUsed / dist) * 100f else 0.0f
            }

        val avgSpeed: Float
            get() = if (speedSamples > 0) speedSum / speedSamples else 0.0f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("skydash_trips", Context.MODE_PRIVATE)
    private val gson = Gson()

    var tripA = loadTrip("A")
    var tripB = loadTrip("B")
    var tripC = loadTrip("C")

    private fun loadTrip(id: String): TripData {
        val json = prefs.getString("trip_$id", null)
        return if (json != null) {
            try {
                gson.fromJson(json, TripData::class.java)
            } catch (e: Exception) {
                TripData(id)
            }
        } else {
            TripData(id)
        }
    }

    fun saveTrip(trip: TripData) {
        prefs.edit().putString("trip_${trip.id}", gson.toJson(trip)).apply()
    }

    fun resetTrip(id: String) {
        val newTrip = TripData(id)
        when (id) {
            "A" -> tripA = newTrip
            "B" -> tripB = newTrip
            "C" -> tripC = newTrip
        }
        saveTrip(newTrip)
    }

    fun update(snapshot: VehicleSnapshot) {
        updateSingleTrip(tripA, snapshot)
        updateSingleTrip(tripB, snapshot)
        updateSingleTrip(tripC, snapshot)
    }

    private fun updateSingleTrip(trip: TripData, snapshot: VehicleSnapshot) {
        val now = System.currentTimeMillis()
        
        // 1. Initialize starting values if not set
        if (trip.startOdo < 0) {
            trip.startOdo = snapshot.odometer
            trip.startEnergy = snapshot.realEnergy
            trip.startSoc = snapshot.soc
            trip.startTime = now
            trip.lastUpdateTime = now
        }

        // 2. Prevent negative energy or odometer readings (e.g. if values are corrupted or charging)
        if (snapshot.odometer < trip.startOdo) {
            trip.startOdo = snapshot.odometer
        }
        
        // If battery was charged, adjust startEnergy to avoid huge negative consumption
        if (snapshot.realEnergy < trip.startEnergy || snapshot.chargeState > 0) {
            trip.startEnergy = snapshot.realEnergy
        }

        trip.currentOdo = snapshot.odometer
        trip.currentEnergy = snapshot.realEnergy
        trip.currentSoc = snapshot.soc

        // 3. Durations: Active (driving) vs Parked
        val elapsedSec = if (trip.lastUpdateTime > 0) (now - trip.lastUpdateTime) / 1000L else 0L
        if (elapsedSec > 0) {
            if (snapshot.speed > 1.0f) {
                trip.activeDuration += elapsedSec
                // Accumulate speed samples for average speed
                trip.speedSum += snapshot.speed
                trip.speedSamples++
            } else {
                trip.parkDuration += elapsedSec
            }
        }

        // 4. Klima (AC) Energy consumption estimation
        // Since we don't have direct HVAC compressor energy, we simulate it
        // when compressor is on: typical 1.5 kW draw, otherwise fan draws 0.1 kW.
        if (trip.lastKlimaTime > 0) {
            val deltaKlimaHr = (now - trip.lastKlimaTime) / (1000.0 * 3600.0)
            if (deltaKlimaHr > 0) {
                val powerKw = if (snapshot.hvacPower) {
                    if (snapshot.hvacAuto || snapshot.hvacFanSpeed > 3) 1.5f else 0.8f
                } else {
                    0.0f
                }
                trip.klimaEnergy += (powerKw * deltaKlimaHr).toFloat()
            }
        }
        trip.lastKlimaTime = now
        trip.lastUpdateTime = now

        saveTrip(trip)
    }

    fun getCost(energyUsed: Float): Float {
        // Read unit price from Settings SharedPreferences (default 2.20 TL/kWh)
        val settingsPrefs = context.getSharedPreferences("SkyDashSettings", Context.MODE_PRIVATE)
        val acPrice = settingsPrefs.getFloat("ac_price", 2.20f)
        return energyUsed * acPrice
    }
}

package com.skywell.skydash.trip

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.skywell.skydash.data.SkyDashDatabase
import com.skywell.skydash.data.VehicleSnapshot

class ChargeTracker(private val context: Context) {
    private val TAG = "ChargeTracker"
    private val dbHelper = SkyDashDatabase(context)
    
    private var isCharging = false
    private var startSoc = 0
    private var startEnergy = 0.0f
    private var startTimestamp = 0L

    fun onSnapshot(snapshot: VehicleSnapshot) {
        val now = System.currentTimeMillis()
        val chargeState = snapshot.chargeState
        val currentlyCharging = chargeState > 0 // 1: AC, 2: DC, >0: Charging

        if (currentlyCharging && !isCharging) {
            // Charging started
            isCharging = true
            startSoc = snapshot.soc
            startEnergy = snapshot.realEnergy
            startTimestamp = now
            Log.i(TAG, "Charging started. SOC: $startSoc%, Energy: $startEnergy kWh")
        } else if (!currentlyCharging && isCharging) {
            // Charging stopped
            isCharging = false
            val endSoc = snapshot.soc
            val endEnergy = snapshot.realEnergy
            val durationMin = ((now - startTimestamp) / 60000L).toInt()

            if (endSoc > startSoc) {
                // Calculate energy added:
                // If realEnergy decreases on charge (negative delta consumed), use absolute delta.
                // Otherwise calculate based on 68 kWh battery capacity.
                val energyDeltaConsumed = startEnergy - endEnergy
                val energyAdded = if (energyDeltaConsumed > 0.1f) {
                    energyDeltaConsumed
                } else {
                    (endSoc - startSoc) / 100.0f * 68.0f
                }

                // Determine AC vs DC charging type
                val chargeType = if (chargeState == 2 || (energyAdded / (durationMin / 60.0f)) > 11.0f) "DC" else "AC"

                // Read appropriate unit price from settings
                val settingsPrefs = context.getSharedPreferences("SkyDashSettings", Context.MODE_PRIVATE)
                val priceKey = if (chargeType == "DC") "dc_price" else "ac_price"
                val defaultPrice = if (chargeType == "DC") 8.50f else 2.20f
                val unitPrice = settingsPrefs.getFloat(priceKey, defaultPrice)
                val cost = energyAdded * unitPrice

                saveChargeSession(startTimestamp, chargeType, startSoc, endSoc, energyAdded, cost)
            }
        }
    }

    private fun saveChargeSession(timestamp: Long, type: String, startSoc: Int, endSoc: Int, energyAdded: Float, cost: Float) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("type", type)
            put("start_soc", startSoc)
            put("end_soc", endSoc)
            put("energy_added", energyAdded)
            put("cost", cost)
        }
        db.insert("charge_sessions", null, values)
        Log.i(TAG, "Saved charge session: $startSoc% -> $endSoc%, Energy: $energyAdded kWh, Cost: $cost TL")
    }
}

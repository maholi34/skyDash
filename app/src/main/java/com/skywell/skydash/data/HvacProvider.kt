package com.skywell.skydash.data

import android.content.Context
import android.net.Uri
import android.util.Log

object HvacProvider {
    private const val TAG = "HvacProvider"
    private val CONTENT_URI = Uri.parse("content://com.coolwell.ai.skyhvac.database/airconditioner")

    data class HvacData(
        val power: Boolean,
        val auto: Boolean,
        val compressor: Boolean,
        val targetTemp: Float,
        val fanSpeed: Int,
        val insideTemp: Float,
        val outsideTemp: Float,
        val vtmsMode: Int
    )

    fun queryHvac(context: Context): HvacData? {
        val resolver = context.contentResolver
        var cursor: android.database.Cursor? = null
        try {
            cursor = resolver.query(CONTENT_URI, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                // Columns: power, auto, compressor, temperature, windlevel (or blowing), insidetemperature, outsidetemperature, vtmsmode
                
                // Safe column reading helper
                fun getInt(colName: String, default: Int): Int {
                    val idx = cursor.getColumnIndex(colName)
                    return if (idx >= 0) cursor.getInt(idx) else default
                }
                
                fun getFloat(colName: String, default: Float): Float {
                    val idx = cursor.getColumnIndex(colName)
                    return if (idx >= 0) cursor.getFloat(idx) else default
                }

                fun getBool(colName: String, default: Boolean): Boolean {
                    val idx = cursor.getColumnIndex(colName)
                    return if (idx >= 0) cursor.getInt(idx) == 1 else default
                }

                val power = getBool("power", false)
                val auto = getBool("auto", false)
                val compressor = getBool("compressor", false)
                val targetTemp = getFloat("temperature", 22.0f)
                val fanSpeed = getInt("windlevel", getInt("blowing", 1))
                val insideTemp = getFloat("insidetemperature", 22.0f)
                val outsideTemp = getFloat("outsidetemperature", 18.0f)
                val vtmsMode = getInt("vtmsmode", 0)

                return HvacData(
                    power = power,
                    auto = auto,
                    compressor = compressor,
                    targetTemp = targetTemp,
                    fanSpeed = fanSpeed,
                    insideTemp = insideTemp,
                    outsideTemp = outsideTemp,
                    vtmsMode = vtmsMode
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying HVAC content provider: ${e.message}")
        } finally {
            cursor?.close()
        }
        return null
    }
}

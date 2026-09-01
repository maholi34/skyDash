package com.skywell.skydash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.skywell.skydash.data.CarServiceParser
import com.skywell.skydash.data.HvacProvider
import com.skywell.skydash.data.SkyDashDatabase
import com.skywell.skydash.data.VehicleSnapshot
import com.skywell.skydash.trip.BlackBoxRecorder
import com.skywell.skydash.trip.ChargeTracker
import com.skywell.skydash.trip.TripManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VehicleDataService : Service() {
    private val TAG = "VehicleDataService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "skydash_service_channel"

    private val binder = LocalBinder()
    private lateinit var adbClient: LocalAdbClient
    private lateinit var tripManager: TripManager
    private lateinit var blackBoxRecorder: BlackBoxRecorder
    private lateinit var chargeTracker: ChargeTracker
    private lateinit var dbHelper: SkyDashDatabase
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val gson = Gson()

    var onDataUpdated: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): VehicleDataService = this@VehicleDataService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Starting VehicleDataService...")

        adbClient = LocalAdbClient(this)
        tripManager = TripManager(this)
        blackBoxRecorder = BlackBoxRecorder(this)
        chargeTracker = ChargeTracker(this)
        dbHelper = SkyDashDatabase(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        serviceScope.launch {
            val adbConnected = adbClient.connect()
            if (adbConnected) {
                Log.i(TAG, "ADB Connection success. Starting polling loops.")
            } else {
                Log.w(TAG, "ADB offline. Dashboard will run on simulations or fallbacks.")
            }
            startPolling()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SkyDash Vehicle Data Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SkyDash Aktif")
            .setContentText("Araç sensör verileri okunuyor...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startPolling() {
        var lastIgnitionState = 0
        serviceScope.launch {
            while (isActive) {
                try {
                    // 1. Query HvacContentProvider (Rootless)
                    val hvacData = HvacProvider.queryHvac(this@VehicleDataService)

                    // 2. Query VehicleHAL dumpsys via LocalADB
                    val rawData = adbClient.executeCommand("dumpsys car_service | grep lastEvent")

                    // 3. Parse snapshot (if rawData is empty, parser uses defaults/hvacData)
                    val snapshot = CarServiceParser.parse(rawData, hvacData)

                    // 4. Update managers and trackers
                    tripManager.update(snapshot)
                    blackBoxRecorder.onSnapshot(snapshot)
                    chargeTracker.onSnapshot(snapshot)

                    // 5. Track ignition OFF transition to close Trip A
                    if (snapshot.ignitionState == 0 && lastIgnitionState >= 2) {
                        Log.i(TAG, "Ignition turned OFF. Committing Trip A data and resetting.")
                        
                        // Sync final precise Trip A values to BlackBox trips table before resetting
                        val finalDist = tripManager.tripA.distance
                        val finalEnergy = tripManager.tripA.energyUsed
                        val finalCost = tripManager.getCost(finalEnergy)
                        blackBoxRecorder.updateFinalTripStats(finalDist, finalEnergy, finalCost)

                        // Reset Trip A
                        tripManager.resetTrip("A")
                    }
                    lastIgnitionState = snapshot.ignitionState

                    // 6. Build UI JSON data
                    val uiJson = buildUiJson(snapshot)
                    onDataUpdated?.invoke(uiJson)

                } catch (e: Exception) {
                    Log.e(TAG, "Error in polling loop: ${e.message}", e)
                }

                delay(2000) // Poll every 2 seconds
            }
        }
    }

    private fun buildUiJson(snapshot: VehicleSnapshot): String {
        val dateFormat = SimpleDateFormat("dd MMMM yyyy - HH:mm", Locale("tr"))
        val shortDateFormat = SimpleDateFormat("dd MMMM HH:mm", Locale("tr"))

        // Fetch past charge sessions from DB
        val pastCharges = mutableListOf<Map<String, Any>>()
        val db = dbHelper.readableDatabase
        val chargeCursor = db.rawQuery("SELECT timestamp, type, start_soc, end_soc, energy_added, cost FROM charge_sessions ORDER BY timestamp DESC LIMIT 10", null)
        while (chargeCursor.moveToNext()) {
            val dateStr = dateFormat.format(Date(chargeCursor.getLong(0)))
            pastCharges.add(mapOf(
                "date" to dateStr,
                "type" to chargeCursor.getString(1),
                "soc" to "%${chargeCursor.getInt(2)} ➔ %${chargeCursor.getInt(3)}",
                "energy" to String.format(Locale.US, "%.1f kWh", chargeCursor.getFloat(4)),
                "cost" to String.format(Locale.US, "%.2f TL", chargeCursor.getFloat(5))
            ))
        }
        chargeCursor.close()

        // Fetch past trips from DB
        val pastTrips = mutableListOf<Map<String, Any>>()
        val tripCursor = db.rawQuery("SELECT id, start_time, distance, duration, energy_used, avg_consumption FROM trips WHERE end_time > 0 ORDER BY start_time DESC LIMIT 10", null)
        while (tripCursor.moveToNext()) {
            val id = tripCursor.getLong(0)
            val dateStr = dateFormat.format(Date(tripCursor.getLong(1)))
            val dist = tripCursor.getFloat(2)
            val durSec = tripCursor.getInt(3)
            val durationStr = "${durSec / 60} Dk"
            
            pastTrips.add(mapOf(
                "id" to id,
                "date" to dateStr,
                "distance" to String.format(Locale.US, "%.1f km", dist),
                "duration" to durationStr,
                "energy" to String.format(Locale.US, "%.1f kWh", tripCursor.getFloat(4)),
                "avg" to String.format(Locale.US, "%.1f kWh/100km", tripCursor.getFloat(5))
            ))
        }
        tripCursor.close()

        // Construct full UI model
        val dataMap = mapOf(
            "adbConnected" to adbClient.isConnected,
            "soc" to snapshot.soc,
            "range" to snapshot.range,
            "speed" to snapshot.speed,
            "odometer" to snapshot.odometer,
            "realEnergy" to snapshot.realEnergy,
            "insideTemp" to snapshot.insideTemp,
            "outsideTemp" to snapshot.outsideTemp,
            "hvacFanSpeed" to snapshot.hvacFanSpeed,
            "hvacTargetTemp" to snapshot.hvacTargetTemp,
            "hvacAuto" to snapshot.hvacAuto,
            "hvacPower" to snapshot.hvacPower,
            "regenLevel" to snapshot.regenLevel,
            "chargeState" to snapshot.chargeState,
            "ignitionState" to snapshot.ignitionState,
            "trips" to mapOf(
                "A" to serializeTrip(tripManager.tripA),
                "B" to serializeTrip(tripManager.tripB),
                "C" to serializeTrip(tripManager.tripC)
            ),
            "pastCharges" to pastCharges,
            "pastTrips" to pastTrips
        )

        return gson.toJson(dataMap)
    }

    private fun serializeTrip(trip: TripManager.TripData): Map<String, Any> {
        val activeMin = trip.activeDuration / 60
        val activeSec = trip.activeDuration % 60
        val activeStr = if (activeMin > 60) "${activeMin / 60} sa ${activeMin % 60} dk" else "$activeMin dk $activeSec sn"

        val parkMin = trip.parkDuration / 60
        val parkStr = "${parkMin / 60} sa ${parkMin % 60} dk"

        val cost = tripManager.getCost(trip.energyUsed)

        return mapOf(
            "distance" to String.format(Locale.US, "%.2f km", trip.distance),
            "energyUsed" to String.format(Locale.US, "%.2f kWh", trip.energyUsed),
            "socDelta" to String.format(Locale.US, "%.1f%%", trip.socDeltaPercent),
            "avgConsumption" to String.format(Locale.US, "%.1f kWh/100km", trip.avgConsumption),
            "avgSpeed" to String.format(Locale.US, "%.1f km/h", trip.avgSpeed),
            "activeDuration" to activeStr,
            "parkDuration" to parkStr,
            "cost" to String.format(Locale.US, "%.2f TL", cost),
            "costPerKm" to String.format(Locale.US, "%.2f TL/km", if (trip.distance > 0.1f) cost / trip.distance else 0.0f)
        )
    }

    fun manualResetTrip(id: String) {
        tripManager.resetTrip(id)
    }

    fun getTelemetryForTrip(tripId: Long): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT timestamp, soc, speed, altitude, motor_temp, cell_diff_mv FROM telemetry WHERE trip_id = ? ORDER BY timestamp ASC", arrayOf(tripId.toString()))
        while (cursor.moveToNext()) {
            list.add(mapOf(
                "time" to cursor.getInt(0), // relative seconds
                "soc" to cursor.getInt(1),
                "speed" to cursor.getFloat(2),
                "altitude" to cursor.getFloat(3),
                "motor_temp" to cursor.getFloat(4),
                "cell_diff_mv" to cursor.getInt(5)
            ))
        }
        cursor.close()
        return list
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        adbClient.disconnect()
        Log.i(TAG, "VehicleDataService stopped.")
    }
}

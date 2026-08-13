package com.skywell.skydash.data

data class VehicleSnapshot(
    val soc: Int,                    // CHARGE_ACTUCAL_SOC (%)
    val range: Int,                  // CHARGE_RESI_MILG (km)
    val speed: Float,                // PERF_VEHICLE_SPEED (km/h)
    val odometer: Float,             // PERF_ODOMETER (km)
    val realEnergy: Float,           // CHARGE_REAL_ENERGY (kWh)
    val chargeState: Int,            // CHARGE_STATE (0: not charging, 1: AC, 2: DC, etc)
    val ignitionState: Int,          // IGNITION_STATE (0: LOCK/OFF, 1: ACC, 2: ON, 3: START)
    val insideTemp: Float,           // ENV_INSIDE_TEMPERATURE
    val outsideTemp: Float,          // ENV_OUTSIDE_TEMPERATURE
    val hvacFanSpeed: Int,           // HVAC_FAN_SPEED (1-7)
    val hvacTargetTemp: Float,       // HVAC Target Temperature
    val hvacAuto: Boolean,           // HVAC Auto mode
    val hvacPower: Boolean,          // HVAC Power ON/OFF
    val regenLevel: Int,             // ENERGY_RECOVERY_MODEL
    val timestamp: Long              // System.currentTimeMillis()
)

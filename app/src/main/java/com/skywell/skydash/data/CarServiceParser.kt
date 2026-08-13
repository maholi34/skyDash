package com.skywell.skydash.data

import android.util.Log
import java.util.regex.Pattern

object CarServiceParser {
    private const val TAG = "CarServiceParser"

    // Property Constants
    private const val PROP_SOC_HEX = "0x1160031b"
    private const val PROP_SOC_DEC = "291504923"
    
    private const val PROP_SOC_BACKUP_HEX = "0x11600309"
    private const val PROP_SOC_BACKUP_DEC = "291504905"

    private const val PROP_ODOMETER_HEX = "0x11600204"
    private const val PROP_ODOMETER_DEC = "291503620"

    private const val PROP_SPEED_HEX = "0x11600207"
    private const val PROP_SPEED_DEC = "291503623"

    private const val PROP_ENERGY_HEX = "0x11600332"
    private const val PROP_ENERGY_DEC = "291503922"

    private const val PROP_RANGE_HEX = "0x11600333"
    private const val PROP_RANGE_DEC = "291503923"

    private const val PROP_CHARGE_STATE_HEX = "0x11400330"
    private const val PROP_CHARGE_STATE_DEC = "289407792"

    private const val PROP_IGNITION_HEX = "0x11400409"
    private const val PROP_IGNITION_DEC = "289407945"

    private const val PROP_INSIDE_TEMP_HEX = "0x11600517"
    private const val PROP_INSIDE_TEMP_DEC = "291504407"

    private const val PROP_OUTSIDE_TEMP_HEX = "0x11600518"
    private const val PROP_OUTSIDE_TEMP_DEC = "291504408"

    private const val PROP_HVAC_FAN_HEX = "0x15400500"
    private const val PROP_HVAC_FAN_DEC = "356517120"

    private const val PROP_REGEN_HEX = "0x11400432"
    private const val PROP_REGEN_DEC = "289408050"

    fun parse(rawText: String, hvacData: HvacProvider.HvacData?): VehicleSnapshot {
        val lines = rawText.split("\n")
        
        var soc = -1
        var socBackup = -1
        var odometer = 0.0f
        var speed = 0.0f
        var realEnergy = 0.0f
        var range = 0
        var chargeState = 0
        var ignitionState = 0
        var insideTemp = 0.0f
        var outsideTemp = 0.0f
        var hvacFanSpeed = 0
        var regenLevel = 0 // Default changed to 0 to indicate failure

        // Regex pattern to extract value inside brackets [val] or raw numbers
        val bracketPattern = Pattern.compile("\\[([\\d.-]+)\\]")
        val numericPattern = Pattern.compile("(?:value|val)[:=]?\\s*([\\d.-]+)", Pattern.CASE_INSENSITIVE)
        val simpleNumberPattern = Pattern.compile("[:\\s]([\\d.-]+)$")

        for (line in lines) {
            val lowerLine = line.trim().lowercase()
            if (lowerLine.isEmpty()) continue

            // Determine if this line belongs to one of our properties
            val propId = when {
                lowerLine.contains(PROP_SOC_HEX) || lowerLine.contains(PROP_SOC_DEC) -> "soc"
                lowerLine.contains(PROP_SOC_BACKUP_HEX) || lowerLine.contains(PROP_SOC_BACKUP_DEC) -> "soc_backup"
                lowerLine.contains(PROP_ODOMETER_HEX) || lowerLine.contains(PROP_ODOMETER_DEC) -> "odometer"
                lowerLine.contains(PROP_SPEED_HEX) || lowerLine.contains(PROP_SPEED_DEC) -> "speed"
                lowerLine.contains(PROP_ENERGY_HEX) || lowerLine.contains(PROP_ENERGY_DEC) -> "energy"
                lowerLine.contains(PROP_RANGE_HEX) || lowerLine.contains(PROP_RANGE_DEC) -> "range"
                lowerLine.contains(PROP_CHARGE_STATE_HEX) || lowerLine.contains(PROP_CHARGE_STATE_DEC) -> "charge_state"
                lowerLine.contains(PROP_IGNITION_HEX) || lowerLine.contains(PROP_IGNITION_DEC) -> "ignition"
                lowerLine.contains(PROP_INSIDE_TEMP_HEX) || lowerLine.contains(PROP_INSIDE_TEMP_DEC) -> "inside_temp"
                lowerLine.contains(PROP_OUTSIDE_TEMP_HEX) || lowerLine.contains(PROP_OUTSIDE_TEMP_DEC) -> "outside_temp"
                lowerLine.contains(PROP_HVAC_FAN_HEX) || lowerLine.contains(PROP_HVAC_FAN_DEC) -> "hvac_fan"
                lowerLine.contains(PROP_REGEN_HEX) || lowerLine.contains(PROP_REGEN_DEC) -> "regen"
                else -> null
            } ?: continue

            // Try to extract the number from the line
            var extractedValue: Float? = null
            
            // 1. Try brackets e.g. [78] or [78.0]
            val bracketMatcher = bracketPattern.matcher(line)
            if (bracketMatcher.find()) {
                extractedValue = bracketMatcher.group(1)?.toFloatOrNull()
            }
            
            // 2. Try value: 78
            if (extractedValue == null) {
                val numericMatcher = numericPattern.matcher(line)
                if (numericMatcher.find()) {
                    extractedValue = numericMatcher.group(1)?.toFloatOrNull()
                }
            }

            // 3. Try fallback ending numbers
            if (extractedValue == null) {
                val simpleMatcher = simpleNumberPattern.matcher(line)
                if (simpleMatcher.find()) {
                    extractedValue = simpleMatcher.group(1)?.toFloatOrNull()
                }
            }

            if (extractedValue != null) {
                when (propId) {
                    "soc" -> soc = extractedValue.toInt()
                    "soc_backup" -> socBackup = extractedValue.toInt()
                    "odometer" -> odometer = extractedValue
                    "speed" -> speed = extractedValue
                    "energy" -> realEnergy = extractedValue
                    "range" -> range = extractedValue.toInt()
                    "charge_state" -> chargeState = extractedValue.toInt()
                    "ignition" -> ignitionState = extractedValue.toInt()
                    "inside_temp" -> insideTemp = extractedValue
                    "outside_temp" -> outsideTemp = extractedValue
                    "hvac_fan" -> hvacFanSpeed = extractedValue.toInt()
                    "regen" -> regenLevel = extractedValue.toInt()
                }
            }
        }

        // Apply fallback for SOC if main SOC wasn't parsed
        if (soc == -1) {
            soc = if (socBackup != -1) socBackup else 0
        }

        // Blend with ContentProvider HVAC details if available
        val finalInsideTemp = hvacData?.insideTemp ?: insideTemp
        val finalOutsideTemp = hvacData?.outsideTemp ?: outsideTemp
        val finalHvacFanSpeed = hvacData?.fanSpeed ?: hvacFanSpeed
        val targetTemp = hvacData?.targetTemp ?: 0.0f
        val autoHvac = hvacData?.auto ?: false
        val powerHvac = hvacData?.power ?: false

        return VehicleSnapshot(
            soc = soc,
            range = range,
            speed = speed,
            odometer = odometer,
            realEnergy = realEnergy,
            chargeState = chargeState,
            ignitionState = ignitionState,
            insideTemp = finalInsideTemp,
            outsideTemp = finalOutsideTemp,
            hvacFanSpeed = finalHvacFanSpeed,
            hvacTargetTemp = targetTemp,
            hvacAuto = autoHvac,
            hvacPower = powerHvac,
            regenLevel = regenLevel,
            timestamp = System.currentTimeMillis()
        )
    }
}

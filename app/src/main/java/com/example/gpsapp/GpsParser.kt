package com.example.gpsapp

object GpsParser {
    fun parseGpggaSentence(sentence: String): GpsFix? {
        if (!sentence.startsWith("\$GPGGA") && !sentence.startsWith("\$GNGGA")) return null
        val parts = sentence.split(",")
        if (parts.size < 10) return null

        try {
            val rawLat = parts[2]
            val latDir = parts[3]
            val rawLon = parts[4]
            val lonDir = parts[5]
            val altitude = parts[9].toDoubleOrNull() ?: return null

            val lat = toDecimalDegrees(rawLat, latDir)
            val lon = toDecimalDegrees(rawLon, lonDir)
            return GpsFix(lat, lon, altitude)
        } catch (e: Exception) {
            return null
        }
    }

    private fun toDecimalDegrees(raw: String, direction: String): Double {
        if (raw.isEmpty() || direction.isEmpty()) return 0.0
        val degreesLength = if (direction == "N" || direction == "S") 2 else 3
        val degrees = raw.substring(0, degreesLength).toDoubleOrNull() ?: return 0.0
        val minutes = raw.substring(degreesLength).toDoubleOrNull() ?: return 0.0
        var decimal = degrees + (minutes / 60.0)
        if (direction == "S" || direction == "W") decimal *= -1
        return decimal
    }
}
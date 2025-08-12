package com.example.gpsapp

data class RecordedSession(
    val readableTime: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double
)
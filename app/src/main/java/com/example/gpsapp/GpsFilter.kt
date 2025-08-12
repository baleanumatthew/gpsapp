package com.example.gpsapp

import kotlin.math.abs

fun filterOutliersMad(values: List<Double>, threshold: Double = 3.5): List<Double> {
    if (values.isEmpty()) return emptyList()

    val median = values.sorted().let {
        val mid = it.size / 2
        if (it.size % 2 == 0) (it[mid - 1] + it[mid]) / 2 else it[mid]
    }

    val deviations = values.map { Math.abs(it - median) }
    val mad = deviations.sorted().let {
        val mid = it.size / 2
        if (it.size % 2 == 0) (it[mid - 1] + it[mid]) / 2 else it[mid]
    }

    return values.filterIndexed { index, value ->
        val modifiedZ = if (mad == 0.0) 0.0 else 0.6745 * (value - median) / mad
        abs(modifiedZ) <= threshold
    }
}
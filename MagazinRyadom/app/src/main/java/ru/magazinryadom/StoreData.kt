package ru.magazinryadom

data class Store(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double
)

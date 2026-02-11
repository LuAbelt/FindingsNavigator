package com.unisaarland.se.findingsnavigator.model

data class Location(
    val filename: String,
    val line: Int? = null,
    val column: Int? = null
)

data class Finding(
    val id: String,
    val category: String,
    val location: Location,
    val tags: MutableSet<String> = mutableSetOf(),
    val extras: Map<String, String> = mapOf()
)

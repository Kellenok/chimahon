package com.canopus.chimareader.data

import kotlinx.serialization.Serializable

@Serializable
data class BookMetadata(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String? = null,
    val cover: String? = null,
    val folder: String? = null,
    val lastAccess: Long = System.currentTimeMillis(),
)
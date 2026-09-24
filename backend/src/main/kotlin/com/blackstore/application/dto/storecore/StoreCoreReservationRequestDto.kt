package com.blackstore.application.dto.storecore

data class StoreCoreReserveLineDto(
    val variantId: String,
    val quantity: Int,
    val expectedPriceVersion: String,
)

data class StoreCoreReservationRequestDto(
    val catalogVersion: String,
    val lines: List<StoreCoreReserveLineDto>,
)

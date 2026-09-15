package com.krstock.v3.data.model

/**
 * Official-list registration identity from KRX KIND.
 * Financial metrics and prices are intentionally not stored here.
 */
data class KospiIssuer(
    val code: String,
    val name: String,
    val sector: String,
    val listingDate: String
)

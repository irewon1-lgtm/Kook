package com.krstock.v3.data.model

/**
 * Official listed-company identity from KRX KIND.
 * Financial metrics and prices are intentionally not stored here.
 */
data class ListedIssuer(
    val code: String,
    val name: String,
    val sector: String,
    val listingDate: String
)

// Compatibility aliases keep the generated market masters explicit without duplicating models.
typealias KospiIssuer = ListedIssuer
typealias KosdaqIssuer = ListedIssuer

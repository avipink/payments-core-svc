package com.digitalbank.payments.domain

import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentStatus
import com.digitalbank.contracts.payments.PaymentType

/**
 * Internal payment domain entity.
 *
 * This is the authoritative internal representation of a payment.
 * It is NEVER exposed directly via the API boundary.
 * [PaymentController] always returns [com.digitalbank.contracts.payments.PaymentResponse]
 * via the mapper.
 */
data class Payment(
    val paymentId: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: MonetaryAmount,
    val type: PaymentType,
    val status: PaymentStatus,
    val reference: String?,
    val createdAt: String
)

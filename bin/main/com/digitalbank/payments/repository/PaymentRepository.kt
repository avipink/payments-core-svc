package com.digitalbank.payments.repository

import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentResponse
import com.digitalbank.contracts.payments.PaymentStatus
import com.digitalbank.contracts.payments.PaymentType
import com.digitalbank.payments.domain.Payment
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * In-memory payment repository pre-seeded with 3 realistic payments.
 *
 * This is a mock data store for the practice lab. In a production service
 * this would be replaced by a JPA repository backed by a relational database.
 */
@Repository
class PaymentRepository {

    private val payments: MutableMap<String, Payment> = mutableMapOf(
        "PAY-001" to Payment(
            paymentId = "PAY-001",
            fromAccountId = "ACC-001",
            toAccountId = "ACC-002",
            amount = MonetaryAmount("500.00", "USD"),
            type = PaymentType.INTERNAL_TRANSFER,
            status = PaymentStatus.COMPLETED,
            reference = "Rent split Feb",
            createdAt = "2026-02-28T10:15:00Z"
        ),
        "PAY-002" to Payment(
            paymentId = "PAY-002",
            fromAccountId = "ACC-003",
            toAccountId = "ACC-005",
            amount = MonetaryAmount("1200.00", "USD"),
            type = PaymentType.BILL_PAYMENT,
            status = PaymentStatus.COMPLETED,
            reference = "Invoice #INV-2026-02",
            createdAt = "2026-02-25T14:30:00Z"
        ),
        "PAY-003" to Payment(
            paymentId = "PAY-003",
            fromAccountId = "ACC-002",
            toAccountId = "ACC-001",
            amount = MonetaryAmount("250.00", "USD"),
            type = PaymentType.INTERNAL_TRANSFER,
            status = PaymentStatus.PENDING,
            reference = null,
            createdAt = "2026-03-01T08:00:00Z"
        )
    )

    fun findAll(): List<Payment> = payments.values.toList()

    fun findById(paymentId: String): Payment? = payments[paymentId]

    fun findByAccountId(accountId: String): List<Payment> =
        payments.values.filter { it.fromAccountId == accountId || it.toAccountId == accountId }

    fun save(payment: Payment): Payment {
        payments[payment.paymentId] = payment
        return payment
    }

    /**
     * Returns the sum of all outgoing (debit) payment amounts for [accountId]
     * on the current calendar day, using the [createdAt] timestamp prefix.
     *
     * Used by [com.digitalbank.payments.service.PaymentService] to enforce the
     * $10,000 daily outbound limit.
     *
     * Note: "today" is determined by string prefix matching on ISO-8601 dates
     * (YYYY-MM-DD), which is sufficient for this in-memory practice lab.
     */
    fun getDailyTotal(accountId: String, today: String): BigDecimal =
        payments.values
            .filter { it.fromAccountId == accountId && it.createdAt.startsWith(today) }
            .fold(BigDecimal.ZERO) { acc, p -> acc + BigDecimal(p.amount.amount) }

    fun nextId(): String = "PAY-${String.format("%03d", payments.size + 1)}"

    // ---------------------------------------------------------------------------
    // Idempotency store
    //
    // Keyed by "{idempotencyKey}:{fromAccountId}" to prevent cross-account key
    // collisions. Value is Pair<payloadHash, PaymentResponse> — the hash is used
    // to detect same-key-different-payload conflicts (IdempotencyKeyReused).
    //
    // Thread-safety note: this store has the same non-atomic race condition as
    // getDailyTotal() + save(). Acceptable for the in-memory MVP (C8/C9).
    // ---------------------------------------------------------------------------
    private val idempotencyStore: MutableMap<String, Pair<Int, PaymentResponse>> = mutableMapOf()

    fun findByIdempotencyKey(compositeKey: String): Pair<Int, PaymentResponse>? =
        idempotencyStore[compositeKey]

    fun storeIdempotency(compositeKey: String, payloadHash: Int, response: PaymentResponse) {
        idempotencyStore[compositeKey] = Pair(payloadHash, response)
    }
}

package com.digitalbank.payments.repository

import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentResponse
import com.digitalbank.contracts.payments.PaymentStatus
import com.digitalbank.contracts.payments.PaymentType
import com.digitalbank.payments.domain.Payment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class PaymentRepositoryTest {

    private lateinit var repository: PaymentRepository

    private val today: String = Instant.now().atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    @BeforeEach
    fun setUp() {
        repository = PaymentRepository()
    }

    // -------------------------------------------------------------------------
    // Idempotency store
    // -------------------------------------------------------------------------

    @Test
    fun `findByIdempotencyKey - key not present - returns null`() {
        assertThat(repository.findByIdempotencyKey("nonexistent:ACC-001")).isNull()
    }

    @Test
    fun `storeIdempotency then findByIdempotencyKey - returns stored pair`() {
        val response = buildSampleResponse("PAY-TEST-01")
        repository.storeIdempotency("key-1:ACC-001", 12345, response)

        val found = repository.findByIdempotencyKey("key-1:ACC-001")
        assertThat(found).isNotNull
        assertThat(found!!.first).isEqualTo(12345)
        assertThat(found.second.paymentId).isEqualTo("PAY-TEST-01")
    }

    @Test
    fun `storeIdempotency - overwrite with same key - replaces value`() {
        val first = buildSampleResponse("PAY-TEST-01")
        val second = buildSampleResponse("PAY-TEST-02")
        repository.storeIdempotency("key-1:ACC-001", 100, first)
        repository.storeIdempotency("key-1:ACC-001", 200, second)

        val found = repository.findByIdempotencyKey("key-1:ACC-001")!!
        assertThat(found.second.paymentId).isEqualTo("PAY-TEST-02")
    }

    @Test
    fun `storeIdempotency - different composite keys do not collide`() {
        val r1 = buildSampleResponse("PAY-TEST-01")
        val r2 = buildSampleResponse("PAY-TEST-02")
        repository.storeIdempotency("same-key:ACC-001", 1, r1)
        repository.storeIdempotency("same-key:ACC-002", 2, r2)

        assertThat(repository.findByIdempotencyKey("same-key:ACC-001")!!.second.paymentId).isEqualTo("PAY-TEST-01")
        assertThat(repository.findByIdempotencyKey("same-key:ACC-002")!!.second.paymentId).isEqualTo("PAY-TEST-02")
    }

    // -------------------------------------------------------------------------
    // Daily total
    // -------------------------------------------------------------------------

    @Test
    fun `getDailyTotal - no payments today - returns zero`() {
        val total = repository.getDailyTotal("ACC-NEW", today)
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `getDailyTotal - sums only today outgoing payments for the given account`() {
        val payment = Payment(
            paymentId = "PAY-T01",
            fromAccountId = "ACC-X",
            toAccountId = "ACC-Y",
            amount = MonetaryAmount("300.00", "USD"),
            type = PaymentType.INTERNAL_TRANSFER,
            status = PaymentStatus.PENDING,
            reference = null,
            createdAt = "${today}T10:00:00Z"
        )
        repository.save(payment)

        val total = repository.getDailyTotal("ACC-X", today)
        assertThat(total).isEqualByComparingTo(BigDecimal("300.00"))
    }

    @Test
    fun `getDailyTotal - excludes incoming payments for the account`() {
        val incomingPayment = Payment(
            paymentId = "PAY-T02",
            fromAccountId = "ACC-OTHER",
            toAccountId = "ACC-X",
            amount = MonetaryAmount("500.00", "USD"),
            type = PaymentType.INTERNAL_TRANSFER,
            status = PaymentStatus.PENDING,
            reference = null,
            createdAt = "${today}T10:00:00Z"
        )
        repository.save(incomingPayment)

        val total = repository.getDailyTotal("ACC-X", today)
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `getDailyTotal - excludes payments from other days`() {
        val oldPayment = Payment(
            paymentId = "PAY-T03",
            fromAccountId = "ACC-X",
            toAccountId = "ACC-Y",
            amount = MonetaryAmount("5000.00", "USD"),
            type = PaymentType.INTERNAL_TRANSFER,
            status = PaymentStatus.COMPLETED,
            reference = null,
            createdAt = "2026-01-01T10:00:00Z"
        )
        repository.save(oldPayment)

        val total = repository.getDailyTotal("ACC-X", today)
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO)
    }

    // -------------------------------------------------------------------------
    // nextId
    // -------------------------------------------------------------------------

    @Test
    fun `nextId - returns formatted id based on current store size`() {
        // Pre-seeded repo has 3 payments → next is PAY-004
        val id = repository.nextId()
        assertThat(id).isEqualTo("PAY-004")
    }

    @Test
    fun `save then nextId - increments based on store size`() {
        val payment = Payment(
            paymentId = repository.nextId(),
            fromAccountId = "ACC-A",
            toAccountId = "ACC-B",
            amount = MonetaryAmount("10.00", "USD"),
            type = PaymentType.BILL_PAYMENT,
            status = PaymentStatus.PENDING,
            reference = null,
            createdAt = "2026-04-15T10:00:00Z"
        )
        repository.save(payment)
        assertThat(repository.nextId()).isEqualTo("PAY-005")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildSampleResponse(id: String) = PaymentResponse(
        paymentId = id,
        fromAccountId = "ACC-001",
        toAccountId = "ACC-002",
        amount = MonetaryAmount("100.00", "USD"),
        type = PaymentType.INTERNAL_TRANSFER,
        status = PaymentStatus.PENDING,
        reference = null,
        createdAt = "2026-04-15T10:00:00Z"
    )
}

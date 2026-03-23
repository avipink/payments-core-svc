package com.digitalbank.payments.service

import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentError
import com.digitalbank.contracts.payments.PaymentRequest
import com.digitalbank.contracts.payments.PaymentResponse
import com.digitalbank.contracts.payments.PaymentStatus
import com.digitalbank.payments.client.AccountClient
import com.digitalbank.payments.domain.Payment
import com.digitalbank.payments.exception.PaymentDomainException
import com.digitalbank.payments.repository.PaymentRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DAILY_LIMIT = BigDecimal("10000.00")

/**
 * Core payment processing service.
 *
 * Business rules enforced:
 * 1. Source account must exist and be ACTIVE (validated via accounts-core-svc).
 * 2. Destination account must exist and be ACTIVE.
 * 3. Outbound daily total for the source account must not exceed $10,000.
 *
 * All validation failures are surfaced as [PaymentDomainException] wrapping
 * the appropriate [PaymentError] sealed class variant.
 */
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val accountClient: AccountClient
) {

    fun createPayment(request: PaymentRequest): PaymentResponse {
        // 1. Validate source account
        val fromAccount = accountClient.findAccount(request.fromAccountId)
            ?: throw PaymentDomainException(PaymentError.InvalidAccount(request.fromAccountId))

        if (fromAccount.status.name != "ACTIVE") {
            throw PaymentDomainException(PaymentError.InvalidAccount(request.fromAccountId))
        }

        // 2. Validate destination account
        val toAccount = accountClient.findAccount(request.toAccountId)
            ?: throw PaymentDomainException(PaymentError.InvalidAccount(request.toAccountId))

        if (toAccount.status.name != "ACTIVE") {
            throw PaymentDomainException(PaymentError.InvalidAccount(request.toAccountId))
        }

        // 3. Enforce daily outbound limit
        val today = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val dailyTotal = paymentRepository.getDailyTotal(request.fromAccountId, today)
        val requestedAmount = BigDecimal(request.amount.amount)

        if (dailyTotal + requestedAmount > DAILY_LIMIT) {
            throw PaymentDomainException(
                PaymentError.DailyLimitExceeded(
                    limit = MonetaryAmount(DAILY_LIMIT.toPlainString(), request.amount.currency),
                    attempted = MonetaryAmount((dailyTotal + requestedAmount).toPlainString(), request.amount.currency)
                )
            )
        }

        // 4. Create and persist payment
        val payment = Payment(
            paymentId = paymentRepository.nextId(),
            fromAccountId = request.fromAccountId,
            toAccountId = request.toAccountId,
            amount = request.amount,
            type = request.type,
            status = PaymentStatus.COMPLETED,
            reference = request.reference,
            createdAt = Instant.now().toString()
        )

        val saved = paymentRepository.save(payment)
        return toResponse(saved)
    }

    fun getPayment(paymentId: String): PaymentResponse {
        val payment = paymentRepository.findById(paymentId)
            ?: throw PaymentDomainException(PaymentError.InvalidAccount(paymentId))
        return toResponse(payment)
    }

    fun getPaymentsByAccount(accountId: String): List<PaymentResponse> =
        paymentRepository.findByAccountId(accountId).map { toResponse(it) }

    private fun toResponse(payment: Payment): PaymentResponse = PaymentResponse(
        paymentId = payment.paymentId,
        fromAccountId = payment.fromAccountId,
        toAccountId = payment.toAccountId,
        amount = payment.amount,
        type = payment.type,
        status = payment.status,
        reference = payment.reference,
        createdAt = payment.createdAt
    )
}

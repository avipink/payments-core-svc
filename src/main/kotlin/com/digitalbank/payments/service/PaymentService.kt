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
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val DAILY_LIMIT = BigDecimal("10000.00")

/**
 * Core payment processing service.
 *
 * Business rules enforced (in order):
 * 1. Payload validation — amount > 0, non-blank IDs and currency.
 * 2. Idempotency check — duplicate key+payload returns cached response;
 *    duplicate key with different payload returns 409.
 * 3. Source account must exist and be ACTIVE.
 * 4. Currency of request must match source account currency.
 * 5. Source account balance must cover the requested amount.
 * 6. Destination account must exist and be ACTIVE.
 * 7. Outbound daily total must not exceed $10,000.
 *
 * All validation failures are surfaced as [PaymentDomainException] wrapping
 * the appropriate [PaymentError] sealed class variant.
 *
 * Audit logging: every request path (success and all failures) emits a
 * structured SLF4J log entry. traceId is stored in MDC for the duration of
 * the call so GlobalExceptionHandler can reuse the same ID in error responses.
 * PII constraint: balance values, holderName, and amount values MUST NOT appear
 * in any log output — only accountIds, paymentIds, type, status, and error codes.
 */
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val accountClient: AccountClient
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    fun createPayment(request: PaymentRequest, idempotencyKey: String): PaymentResponse {
        val traceId = UUID.randomUUID().toString()
        MDC.put("traceId", traceId)

        try {
            // Step 0a: Service-layer payload validation
            validate(request, traceId)

            // Step 0b: Idempotency check
            val compositeKey = "$idempotencyKey:${request.fromAccountId}"
            val payloadHash = request.hashCode()
            val existing = paymentRepository.findByIdempotencyKey(compositeKey)
            if (existing != null) {
                return if (existing.first == payloadHash) {
                    log.info(
                        "operation=payment.create status=IDEMPOTENT_REPLAY " +
                        "fromAccountId={} toAccountId={} type={} traceId={}",
                        request.fromAccountId, request.toAccountId, request.type, traceId
                    )
                    existing.second
                } else {
                    log.warn(
                        "operation=payment.create errorCode=IDEMPOTENCY_KEY_REUSED " +
                        "fromAccountId={} toAccountId={} type={} traceId={}",
                        request.fromAccountId, request.toAccountId, request.type, traceId
                    )
                    throw PaymentDomainException(PaymentError.IdempotencyKeyReused(idempotencyKey))
                }
            }

            log.info(
                "operation=payment.create status=ACCEPTED " +
                "fromAccountId={} toAccountId={} type={} traceId={}",
                request.fromAccountId, request.toAccountId, request.type, traceId
            )

            // Step 1: Validate source account existence and active status
            val fromAccount = accountClient.findAccount(request.fromAccountId)
                ?: run {
                    log.warn(
                        "operation=payment.create errorCode=PAYMENT_ACCOUNT_NOT_FOUND " +
                        "fromAccountId={} traceId={}",
                        request.fromAccountId, traceId
                    )
                    throw PaymentDomainException(PaymentError.InvalidAccount(request.fromAccountId))
                }

            if (fromAccount.status.name != "ACTIVE") {
                log.warn(
                    "operation=payment.create errorCode=PAYMENT_ACCOUNT_NOT_FOUND " +
                    "fromAccountId={} accountStatus={} traceId={}",
                    request.fromAccountId, fromAccount.status, traceId
                )
                throw PaymentDomainException(PaymentError.InvalidAccount(request.fromAccountId))
            }

            // Step 2a: Currency check — reuses fromAccount already fetched (no extra HTTP call)
            if (fromAccount.balance.currency != request.amount.currency) {
                log.warn(
                    "operation=payment.create errorCode=CURRENCY_MISMATCH " +
                    "fromAccountId={} requestedCurrency={} accountCurrency={} traceId={}",
                    request.fromAccountId, request.amount.currency, fromAccount.balance.currency, traceId
                )
                throw PaymentDomainException(
                    PaymentError.CurrencyMismatch(request.amount.currency, fromAccount.balance.currency)
                )
            }

            // Step 2b: Balance check — reuses fromAccount.balance (no extra HTTP call, per Scope Decision #2)
            val requestedAmount = BigDecimal(request.amount.amount)
            val availableBalance = BigDecimal(fromAccount.balance.amount)
            if (availableBalance < requestedAmount) {
                log.warn(
                    "operation=payment.create errorCode=INSUFFICIENT_FUNDS " +
                    "fromAccountId={} traceId={}",
                    request.fromAccountId, traceId
                )
                throw PaymentDomainException(
                    PaymentError.InsufficientFunds(request.fromAccountId, request.amount, fromAccount.balance)
                )
            }

            // Step 3: Validate destination account existence and active status
            val toAccount = accountClient.findAccount(request.toAccountId)
                ?: run {
                    log.warn(
                        "operation=payment.create errorCode=PAYMENT_ACCOUNT_NOT_FOUND " +
                        "toAccountId={} traceId={}",
                        request.toAccountId, traceId
                    )
                    throw PaymentDomainException(PaymentError.InvalidAccount(request.toAccountId))
                }

            if (toAccount.status.name != "ACTIVE") {
                log.warn(
                    "operation=payment.create errorCode=PAYMENT_ACCOUNT_NOT_FOUND " +
                    "toAccountId={} accountStatus={} traceId={}",
                    request.toAccountId, toAccount.status, traceId
                )
                throw PaymentDomainException(PaymentError.InvalidAccount(request.toAccountId))
            }

            // Step 4: Enforce daily outbound limit
            val today = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val dailyTotal = paymentRepository.getDailyTotal(request.fromAccountId, today)

            if (dailyTotal + requestedAmount > DAILY_LIMIT) {
                log.warn(
                    "operation=payment.create errorCode=DAILY_LIMIT_EXCEEDED " +
                    "fromAccountId={} traceId={}",
                    request.fromAccountId, traceId
                )
                throw PaymentDomainException(
                    PaymentError.DailyLimitExceeded(
                        limit = MonetaryAmount(DAILY_LIMIT.toPlainString(), request.amount.currency),
                        attempted = MonetaryAmount((dailyTotal + requestedAmount).toPlainString(), request.amount.currency)
                    )
                )
            }

            // Step 5: Create and persist payment with status PENDING
            val payment = Payment(
                paymentId = paymentRepository.nextId(),
                fromAccountId = request.fromAccountId,
                toAccountId = request.toAccountId,
                amount = request.amount,
                type = request.type,
                status = PaymentStatus.PENDING,
                reference = request.reference,
                createdAt = Instant.now().toString()
            )

            val saved = paymentRepository.save(payment)
            val response = toResponse(saved)

            // Step 6: Store idempotency entry
            paymentRepository.storeIdempotency(compositeKey, payloadHash, response)

            // Step 7: Audit log success
            log.info(
                "operation=payment.create status=PENDING paymentId={} " +
                "fromAccountId={} toAccountId={} type={} traceId={}",
                response.paymentId, request.fromAccountId, request.toAccountId, request.type, traceId
            )

            return response

        } finally {
            MDC.remove("traceId")
        }
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

    /**
     * Service-layer payload validation. Throws [PaymentDomainException] wrapping
     * [PaymentError.ValidationFailed] if any field fails validation.
     *
     * Validation is performed here (not via Jakarta annotations on [PaymentRequest])
     * to avoid a transitive dependency on the validation API in banking-contracts.
     */
    private fun validate(request: PaymentRequest, traceId: String) {
        val errors = mutableMapOf<String, String>()

        if (request.fromAccountId.isBlank()) {
            errors["fromAccountId"] = "must not be blank"
        }
        if (request.toAccountId.isBlank()) {
            errors["toAccountId"] = "must not be blank"
        }
        if (request.amount.currency.isBlank()) {
            errors["amount.currency"] = "must not be blank"
        }
        val parsedAmount = try {
            BigDecimal(request.amount.amount)
        } catch (ex: NumberFormatException) {
            errors["amount.amount"] = "must be a valid decimal number"
            null
        }
        if (parsedAmount != null && parsedAmount <= BigDecimal.ZERO) {
            errors["amount.amount"] = "must be greater than 0"
        }

        if (errors.isNotEmpty()) {
            log.warn(
                "operation=payment.create errorCode=VALIDATION_FAILED fields={} traceId={}",
                errors.keys.joinToString(","), traceId
            )
            throw PaymentDomainException(PaymentError.ValidationFailed(errors))
        }
    }
}

package com.digitalbank.payments.exception

import com.digitalbank.contracts.common.ApiError
import com.digitalbank.contracts.payments.PaymentError
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant
import java.util.UUID

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(PaymentDomainException::class)
    fun handlePaymentError(ex: PaymentDomainException): ResponseEntity<ApiError> {
        val traceId = UUID.randomUUID().toString()
        val timestamp = Instant.now().toString()

        return when (val error = ex.error) {
            is PaymentError.InvalidAccount -> ResponseEntity.status(404).body(
                ApiError(
                    code = "PAYMENT_ACCOUNT_NOT_FOUND",
                    message = "Account not found or not eligible: ${error.accountId}",
                    traceId = traceId,
                    timestamp = timestamp
                )
            )
            is PaymentError.InsufficientFunds -> ResponseEntity.status(422).body(
                ApiError(
                    code = "INSUFFICIENT_FUNDS",
                    message = "Insufficient funds in account: ${error.accountId}",
                    traceId = traceId,
                    timestamp = timestamp
                )
            )
            is PaymentError.DailyLimitExceeded -> ResponseEntity.status(422).body(
                ApiError(
                    code = "DAILY_LIMIT_EXCEEDED",
                    message = "Daily outbound limit of ${error.limit.currency} ${error.limit.amount} exceeded. Attempted: ${error.attempted.amount}",
                    traceId = traceId,
                    timestamp = timestamp
                )
            )
            is PaymentError.ValidationFailed -> ResponseEntity.status(400).body(
                ApiError(
                    code = "VALIDATION_FAILED",
                    message = error.fieldErrors.entries.joinToString("; ") { (field, msg) -> "$field: $msg" },
                    traceId = traceId,
                    timestamp = timestamp
                )
            )
            is PaymentError.IdempotencyKeyReused -> ResponseEntity.status(409).body(
                ApiError(
                    code = "IDEMPOTENCY_KEY_REUSED",
                    message = "Idempotency key '${error.key}' was previously used with a different payload",
                    traceId = traceId,
                    timestamp = timestamp
                )
            )
            is PaymentError.CurrencyMismatch -> ResponseEntity.status(422).body(
                ApiError(
                    code = "CURRENCY_MISMATCH",
                    message = "Request currency ${error.requested} does not match source account currency ${error.accountCurrency}",
                    traceId = traceId,
                    timestamp = timestamp
                )
            )
        }
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(ex: MissingRequestHeaderException): ResponseEntity<ApiError> =
        ResponseEntity.status(400).body(
            ApiError(
                code = "MISSING_HEADER",
                message = "Required header '${ex.headerName}' is missing",
                traceId = UUID.randomUUID().toString(),
                timestamp = Instant.now().toString()
            )
        )
}

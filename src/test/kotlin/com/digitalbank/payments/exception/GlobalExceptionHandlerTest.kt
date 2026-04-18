package com.digitalbank.payments.exception

import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentError
import com.digitalbank.contracts.payments.PaymentRequest
import com.digitalbank.contracts.payments.PaymentType
import com.digitalbank.payments.controller.PaymentController
import com.digitalbank.payments.service.PaymentService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies that GlobalExceptionHandler maps every PaymentError variant to the
 * correct HTTP status code and ApiError.code value.
 *
 * Note: stubs use exact request objects (not ArgumentMatchers.any()) to avoid
 * NPE from Mockito returning null for Kotlin non-nullable parameter types.
 * The exact object is constructed to match what Spring's Jackson deserializer
 * produces from minimalBody.
 */
@WebMvcTest(PaymentController::class)
class GlobalExceptionHandlerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var paymentService: PaymentService

    /**
     * Exact PaymentRequest object that Spring deserializes from minimalBody.
     * Used in given() stubs so Mockito's equals-based matching works.
     */
    private val minimalRequest = PaymentRequest(
        fromAccountId = "ACC-001",
        toAccountId = "ACC-002",
        amount = MonetaryAmount("100.00", "USD"),
        type = PaymentType.INTERNAL_TRANSFER,
        reference = null
    )

    private val minimalBody = """
        {"fromAccountId":"ACC-001","toAccountId":"ACC-002",
         "amount":{"amount":"100.00","currency":"USD"},
         "type":"INTERNAL_TRANSFER","reference":null}
    """.trimIndent()

    // -------------------------------------------------------------------------
    // PaymentDomainException variants
    // -------------------------------------------------------------------------

    @Test
    fun `InvalidAccount - maps to 404 PAYMENT_ACCOUNT_NOT_FOUND`() {
        given(paymentService.createPayment(minimalRequest, "key"))
            .willThrow(PaymentDomainException(PaymentError.InvalidAccount("ACC-001")))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PAYMENT_ACCOUNT_NOT_FOUND"))
            .andExpect(jsonPath("$.traceId").isNotEmpty)
            .andExpect(jsonPath("$.timestamp").isNotEmpty)
    }

    @Test
    fun `InsufficientFunds - maps to 422 INSUFFICIENT_FUNDS`() {
        given(paymentService.createPayment(minimalRequest, "key"))
            .willThrow(PaymentDomainException(
                PaymentError.InsufficientFunds("ACC-001", MonetaryAmount("100.00", "USD"), MonetaryAmount("50.00", "USD"))
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
    }

    @Test
    fun `DailyLimitExceeded - maps to 422 DAILY_LIMIT_EXCEEDED`() {
        given(paymentService.createPayment(minimalRequest, "key"))
            .willThrow(PaymentDomainException(
                PaymentError.DailyLimitExceeded(
                    MonetaryAmount("10000.00", "USD"),
                    MonetaryAmount("10100.00", "USD")
                )
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"))
    }

    @Test
    fun `ValidationFailed - maps to 400 VALIDATION_FAILED with field errors in message`() {
        given(paymentService.createPayment(minimalRequest, "key"))
            .willThrow(PaymentDomainException(
                PaymentError.ValidationFailed(mapOf("fromAccountId" to "must not be blank"))
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value(containsString("fromAccountId")))
    }

    @Test
    fun `IdempotencyKeyReused - maps to 409 IDEMPOTENCY_KEY_REUSED`() {
        given(paymentService.createPayment(minimalRequest, "conflict-key"))
            .willThrow(PaymentDomainException(PaymentError.IdempotencyKeyReused("conflict-key")))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "conflict-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            .andExpect(jsonPath("$.message").value(containsString("conflict-key")))
    }

    @Test
    fun `CurrencyMismatch - maps to 422 CURRENCY_MISMATCH`() {
        given(paymentService.createPayment(minimalRequest, "key"))
            .willThrow(PaymentDomainException(
                PaymentError.CurrencyMismatch(requested = "USD", accountCurrency = "EUR")
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"))
            .andExpect(jsonPath("$.message").value(containsString("USD")))
            .andExpect(jsonPath("$.message").value(containsString("EUR")))
    }

    // -------------------------------------------------------------------------
    // MissingRequestHeaderException
    // -------------------------------------------------------------------------

    @Test
    fun `missing Idempotency-Key header - maps to 400 MISSING_HEADER`() {
        mockMvc.perform(
            post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(minimalBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
            .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")))
    }

    // -------------------------------------------------------------------------
    // ApiError response structure
    // -------------------------------------------------------------------------

    @Test
    fun `error response always includes traceId and timestamp fields`() {
        given(paymentService.getPayment("PAY-999"))
            .willThrow(PaymentDomainException(PaymentError.InvalidAccount("PAY-999")))

        mockMvc.perform(get("/api/v1/payments/PAY-999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").exists())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(jsonPath("$.timestamp").exists())
    }
}

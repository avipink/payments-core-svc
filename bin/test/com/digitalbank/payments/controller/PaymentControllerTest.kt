package com.digitalbank.payments.controller

import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentError
import com.digitalbank.contracts.payments.PaymentRequest
import com.digitalbank.contracts.payments.PaymentResponse
import com.digitalbank.contracts.payments.PaymentStatus
import com.digitalbank.contracts.payments.PaymentType
import com.digitalbank.payments.exception.PaymentDomainException
import com.digitalbank.payments.service.PaymentService
import com.fasterxml.jackson.databind.ObjectMapper
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

@WebMvcTest(PaymentController::class)
class PaymentControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var paymentService: PaymentService

    private val validRequest = PaymentRequest(
        fromAccountId = "ACC-001",
        toAccountId = "ACC-002",
        amount = MonetaryAmount("100.00", "USD"),
        type = PaymentType.INTERNAL_TRANSFER,
        reference = "Test"
    )

    private val sampleResponse = PaymentResponse(
        paymentId = "PAY-004",
        fromAccountId = "ACC-001",
        toAccountId = "ACC-002",
        amount = MonetaryAmount("100.00", "USD"),
        type = PaymentType.INTERNAL_TRANSFER,
        status = PaymentStatus.PENDING,
        reference = "Test",
        createdAt = "2026-04-15T10:00:00Z"
    )

    // -------------------------------------------------------------------------
    // POST /api/v1/payments
    // -------------------------------------------------------------------------

    @Test
    fun `POST payments - valid request with Idempotency-Key - returns 201`() {
        given(paymentService.createPayment(validRequest, "key-001")).willReturn(sampleResponse)

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.paymentId").value("PAY-004"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.fromAccountId").value("ACC-001"))
    }

    @Test
    fun `POST payments - missing Idempotency-Key header - returns 400 MISSING_HEADER`() {
        mockMvc.perform(
            post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
    }

    @Test
    fun `POST payments - account not found - returns 404 PAYMENT_ACCOUNT_NOT_FOUND`() {
        given(paymentService.createPayment(validRequest, "key-404"))
            .willThrow(PaymentDomainException(PaymentError.InvalidAccount("ACC-001")))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-404")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PAYMENT_ACCOUNT_NOT_FOUND"))
    }

    @Test
    fun `POST payments - idempotency key reused - returns 409 IDEMPOTENCY_KEY_REUSED`() {
        given(paymentService.createPayment(validRequest, "key-conflict"))
            .willThrow(PaymentDomainException(PaymentError.IdempotencyKeyReused("key-conflict")))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
    }

    @Test
    fun `POST payments - insufficient funds - returns 422 INSUFFICIENT_FUNDS`() {
        given(paymentService.createPayment(validRequest, "key-funds"))
            .willThrow(PaymentDomainException(
                PaymentError.InsufficientFunds(
                    accountId = "ACC-001",
                    requested = MonetaryAmount("100.00", "USD"),
                    available = MonetaryAmount("50.00", "USD")
                )
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-funds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
    }

    @Test
    fun `POST payments - currency mismatch - returns 422 CURRENCY_MISMATCH`() {
        given(paymentService.createPayment(validRequest, "key-curr"))
            .willThrow(PaymentDomainException(
                PaymentError.CurrencyMismatch(requested = "USD", accountCurrency = "EUR")
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-curr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"))
    }

    @Test
    fun `POST payments - daily limit exceeded - returns 422 DAILY_LIMIT_EXCEEDED`() {
        given(paymentService.createPayment(validRequest, "key-limit"))
            .willThrow(PaymentDomainException(
                PaymentError.DailyLimitExceeded(
                    limit = MonetaryAmount("10000.00", "USD"),
                    attempted = MonetaryAmount("10100.00", "USD")
                )
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"))
    }

    @Test
    fun `POST payments - validation failed - returns 400 VALIDATION_FAILED`() {
        given(paymentService.createPayment(validRequest, "key-val"))
            .willThrow(PaymentDomainException(
                PaymentError.ValidationFailed(mapOf("fromAccountId" to "must not be blank"))
            ))

        mockMvc.perform(
            post("/api/v1/payments")
                .header("Idempotency-Key", "key-val")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/payments/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `GET payment by id - found - returns 200 with payment`() {
        given(paymentService.getPayment("PAY-004")).willReturn(sampleResponse)

        mockMvc.perform(get("/api/v1/payments/PAY-004"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paymentId").value("PAY-004"))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `GET payment by id - not found - returns 404`() {
        given(paymentService.getPayment("PAY-999"))
            .willThrow(PaymentDomainException(PaymentError.InvalidAccount("PAY-999")))

        mockMvc.perform(get("/api/v1/payments/PAY-999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PAYMENT_ACCOUNT_NOT_FOUND"))
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/payments/account/{accountId}
    // -------------------------------------------------------------------------

    @Test
    fun `GET payments by account - returns 200 list`() {
        given(paymentService.getPaymentsByAccount("ACC-001")).willReturn(listOf(sampleResponse))

        mockMvc.perform(get("/api/v1/payments/account/ACC-001"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].paymentId").value("PAY-004"))
    }

    @Test
    fun `GET payments by account - no payments - returns empty list`() {
        given(paymentService.getPaymentsByAccount("ACC-999")).willReturn(emptyList())

        mockMvc.perform(get("/api/v1/payments/account/ACC-999"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }
}

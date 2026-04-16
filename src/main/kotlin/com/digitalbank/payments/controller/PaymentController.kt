package com.digitalbank.payments.controller

import com.digitalbank.contracts.payments.PaymentRequest
import com.digitalbank.contracts.payments.PaymentResponse
import com.digitalbank.payments.service.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment processing endpoints")
class PaymentController(
    private val paymentService: PaymentService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Submit a payment",
        description = "Validates source and destination accounts, enforces the \$10,000 daily outbound limit, and creates the payment"
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "Payment created successfully"),
        ApiResponse(responseCode = "400", description = "Validation failed or missing Idempotency-Key header"),
        ApiResponse(responseCode = "404", description = "Account not found"),
        ApiResponse(responseCode = "409", description = "Idempotency key reused with different payload"),
        ApiResponse(responseCode = "422", description = "Insufficient funds, currency mismatch, or daily limit exceeded")
    ])
    fun createPayment(
        @RequestBody request: PaymentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String
    ): PaymentResponse =
        paymentService.createPayment(request, idempotencyKey)

    @GetMapping("/{id}")
    @Operation(
        summary = "Get payment by ID",
        description = "Returns details of a single payment"
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Payment found"),
        ApiResponse(responseCode = "404", description = "Payment not found")
    ])
    fun getPayment(@PathVariable id: String): PaymentResponse =
        paymentService.getPayment(id)

    @GetMapping("/account/{accountId}")
    @Operation(
        summary = "List payments for an account",
        description = "Returns all payments where the account is either the source or destination"
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Payments retrieved successfully")
    ])
    fun getPaymentsByAccount(@PathVariable accountId: String): List<PaymentResponse> =
        paymentService.getPaymentsByAccount(accountId)
}

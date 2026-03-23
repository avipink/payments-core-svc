package com.digitalbank.payments.exception

import com.digitalbank.contracts.payments.PaymentError

/**
 * Runtime exception that wraps a typed [PaymentError] for propagation through
 * the Spring MVC exception handling chain.
 *
 * Thrown by the service layer; caught and mapped to HTTP responses by
 * [GlobalExceptionHandler].
 */
class PaymentDomainException(val error: PaymentError) : RuntimeException(error.toString())

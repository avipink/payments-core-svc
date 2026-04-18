package com.digitalbank.payments.service

import com.digitalbank.contracts.accounts.AccountResponse
import com.digitalbank.contracts.accounts.AccountStatus
import com.digitalbank.contracts.accounts.AccountType
import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentError
import com.digitalbank.contracts.payments.PaymentRequest
import com.digitalbank.contracts.payments.PaymentStatus
import com.digitalbank.contracts.payments.PaymentType
import com.digitalbank.payments.client.AccountClient
import com.digitalbank.payments.exception.PaymentDomainException
import com.digitalbank.payments.repository.PaymentRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
class PaymentServiceTest {

    @Mock
    private lateinit var accountClient: AccountClient

    private lateinit var paymentRepository: PaymentRepository
    private lateinit var paymentService: PaymentService

    private val activeUsdAccount = AccountResponse(
        accountId = "ACC-001",
        accountType = AccountType.CHECKING,
        holderName = "Alice",
        balance = MonetaryAmount("5000.00", "USD"),
        status = AccountStatus.ACTIVE,
        currency = "USD",
        lastTransactionDate = null,
        createdAt = "2025-01-01T00:00:00Z"
    )

    private val activeDestAccount = AccountResponse(
        accountId = "ACC-002",
        accountType = AccountType.CHECKING,
        holderName = "Bob",
        balance = MonetaryAmount("1000.00", "USD"),
        status = AccountStatus.ACTIVE,
        currency = "USD",
        lastTransactionDate = null,
        createdAt = "2025-01-01T00:00:00Z"
    )

    private val validRequest = PaymentRequest(
        fromAccountId = "ACC-001",
        toAccountId = "ACC-002",
        amount = MonetaryAmount("100.00", "USD"),
        type = PaymentType.INTERNAL_TRANSFER,
        reference = "Test payment"
    )

    @BeforeEach
    fun setUp() {
        // Fresh repo for each test — no pre-seeded data interference on daily totals
        paymentRepository = PaymentRepository()
        paymentService = PaymentService(paymentRepository, accountClient)
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - happy path - returns PENDING response with assigned paymentId`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeUsdAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        val response = paymentService.createPayment(validRequest, idempotencyKey = "key-001")

        assertThat(response.paymentId).isNotBlank()
        assertThat(response.fromAccountId).isEqualTo("ACC-001")
        assertThat(response.toAccountId).isEqualTo("ACC-002")
        assertThat(response.amount).isEqualTo(MonetaryAmount("100.00", "USD"))
        assertThat(response.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(response.type).isEqualTo(PaymentType.INTERNAL_TRANSFER)
    }

    @Test
    fun `createPayment - status is PENDING not COMPLETED`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeUsdAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        val response = paymentService.createPayment(validRequest, "key-status")

        assertThat(response.status).isEqualTo(PaymentStatus.PENDING)
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - duplicate key and same payload - returns cached response without creating new payment`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeUsdAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        val first = paymentService.createPayment(validRequest, "idem-key-1")
        // Second call with same key+payload hits idempotency store and returns early —
        // accountClient is NOT called again (stubs are not re-registered to avoid UnnecessaryStubbingException)
        val second = paymentService.createPayment(validRequest, "idem-key-1")

        assertThat(second.paymentId).isEqualTo(first.paymentId)
    }

    @Test
    fun `createPayment - duplicate key with different payload - throws IdempotencyKeyReused`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeUsdAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        // First call to register the key
        paymentService.createPayment(validRequest, "idem-key-conflict")

        // Different payload: different amount
        val differentPayload = validRequest.copy(amount = MonetaryAmount("200.00", "USD"))

        assertThatThrownBy { paymentService.createPayment(differentPayload, "idem-key-conflict") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                assertThat((ex as PaymentDomainException).error)
                    .isInstanceOf(PaymentError.IdempotencyKeyReused::class.java)
            })
    }

    // -------------------------------------------------------------------------
    // Validation failures
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - blank fromAccountId - throws ValidationFailed`() {
        val bad = validRequest.copy(fromAccountId = "  ")

        assertThatThrownBy { paymentService.createPayment(bad, "key-v1") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.ValidationFailed::class.java)
                assertThat((error as PaymentError.ValidationFailed).fieldErrors).containsKey("fromAccountId")
            })
    }

    @Test
    fun `createPayment - blank toAccountId - throws ValidationFailed`() {
        val bad = validRequest.copy(toAccountId = "")

        assertThatThrownBy { paymentService.createPayment(bad, "key-v2") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.ValidationFailed::class.java)
                assertThat((error as PaymentError.ValidationFailed).fieldErrors).containsKey("toAccountId")
            })
    }

    @Test
    fun `createPayment - zero amount - throws ValidationFailed`() {
        val bad = validRequest.copy(amount = MonetaryAmount("0", "USD"))

        assertThatThrownBy { paymentService.createPayment(bad, "key-v3") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.ValidationFailed::class.java)
                assertThat((error as PaymentError.ValidationFailed).fieldErrors).containsKey("amount.amount")
            })
    }

    @Test
    fun `createPayment - negative amount - throws ValidationFailed`() {
        val bad = validRequest.copy(amount = MonetaryAmount("-50.00", "USD"))

        assertThatThrownBy { paymentService.createPayment(bad, "key-v4") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.ValidationFailed::class.java)
            })
    }

    @Test
    fun `createPayment - non-numeric amount - throws ValidationFailed`() {
        val bad = validRequest.copy(amount = MonetaryAmount("abc", "USD"))

        assertThatThrownBy { paymentService.createPayment(bad, "key-v5") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.ValidationFailed::class.java)
                assertThat((error as PaymentError.ValidationFailed).fieldErrors).containsKey("amount.amount")
            })
    }

    // -------------------------------------------------------------------------
    // Account validation
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - source account not found - throws InvalidAccount`() {
        given(accountClient.findAccount("ACC-001")).willReturn(null)

        assertThatThrownBy { paymentService.createPayment(validRequest, "key-a1") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.InvalidAccount::class.java)
                assertThat((error as PaymentError.InvalidAccount).accountId).isEqualTo("ACC-001")
            })
    }

    @Test
    fun `createPayment - source account FROZEN - throws InvalidAccount`() {
        val frozenAccount = activeUsdAccount.copy(status = AccountStatus.FROZEN)
        given(accountClient.findAccount("ACC-001")).willReturn(frozenAccount)

        assertThatThrownBy { paymentService.createPayment(validRequest, "key-a2") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                assertThat((ex as PaymentDomainException).error)
                    .isInstanceOf(PaymentError.InvalidAccount::class.java)
            })
    }

    @Test
    fun `createPayment - destination account not found - throws InvalidAccount`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeUsdAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(null)

        assertThatThrownBy { paymentService.createPayment(validRequest, "key-a3") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.InvalidAccount::class.java)
                assertThat((error as PaymentError.InvalidAccount).accountId).isEqualTo("ACC-002")
            })
    }

    @Test
    fun `createPayment - destination account CLOSED - throws InvalidAccount`() {
        val closedAccount = activeDestAccount.copy(status = AccountStatus.CLOSED)
        given(accountClient.findAccount("ACC-001")).willReturn(activeUsdAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(closedAccount)

        assertThatThrownBy { paymentService.createPayment(validRequest, "key-a4") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                assertThat((ex as PaymentDomainException).error)
                    .isInstanceOf(PaymentError.InvalidAccount::class.java)
            })
    }

    // -------------------------------------------------------------------------
    // Currency mismatch
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - currency mismatch - throws CurrencyMismatch`() {
        val eurAccount = activeUsdAccount.copy(
            balance = MonetaryAmount("5000.00", "EUR"),
            currency = "EUR"
        )
        given(accountClient.findAccount("ACC-001")).willReturn(eurAccount)

        val usdRequest = validRequest.copy(amount = MonetaryAmount("100.00", "USD"))

        assertThatThrownBy { paymentService.createPayment(usdRequest, "key-c1") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.CurrencyMismatch::class.java)
                assertThat((error as PaymentError.CurrencyMismatch).requested).isEqualTo("USD")
                assertThat(error.accountCurrency).isEqualTo("EUR")
            })
    }

    // -------------------------------------------------------------------------
    // Insufficient funds
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - amount exceeds balance - throws InsufficientFunds`() {
        val lowBalanceAccount = activeUsdAccount.copy(balance = MonetaryAmount("50.00", "USD"))
        given(accountClient.findAccount("ACC-001")).willReturn(lowBalanceAccount)

        val bigRequest = validRequest.copy(amount = MonetaryAmount("200.00", "USD"))

        assertThatThrownBy { paymentService.createPayment(bigRequest, "key-i1") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                assertThat((ex as PaymentDomainException).error)
                    .isInstanceOf(PaymentError.InsufficientFunds::class.java)
            })
    }

    @Test
    fun `createPayment - exact balance - succeeds`() {
        val exactAccount = activeUsdAccount.copy(balance = MonetaryAmount("100.00", "USD"))
        given(accountClient.findAccount("ACC-001")).willReturn(exactAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        val exactRequest = validRequest.copy(amount = MonetaryAmount("100.00", "USD"))
        val response = paymentService.createPayment(exactRequest, "key-exact")

        assertThat(response.status).isEqualTo(PaymentStatus.PENDING)
    }

    // -------------------------------------------------------------------------
    // Daily limit
    // -------------------------------------------------------------------------

    @Test
    fun `createPayment - single payment exceeding daily limit - throws DailyLimitExceeded`() {
        val richAccount = activeUsdAccount.copy(balance = MonetaryAmount("99999.00", "USD"))
        given(accountClient.findAccount("ACC-001")).willReturn(richAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        val overLimitRequest = validRequest.copy(amount = MonetaryAmount("10001.00", "USD"))

        assertThatThrownBy { paymentService.createPayment(overLimitRequest, "key-d1") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                val error = (ex as PaymentDomainException).error
                assertThat(error).isInstanceOf(PaymentError.DailyLimitExceeded::class.java)
            })
    }

    @Test
    fun `createPayment - cumulative payments reaching daily limit - throws DailyLimitExceeded`() {
        val richAccount = activeUsdAccount.copy(balance = MonetaryAmount("99999.00", "USD"))
        given(accountClient.findAccount("ACC-001")).willReturn(richAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        // First payment just under limit
        val firstRequest = validRequest.copy(amount = MonetaryAmount("9000.00", "USD"))
        paymentService.createPayment(firstRequest, "key-d2a")

        // Reset stubs for second call
        given(accountClient.findAccount("ACC-001")).willReturn(richAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(activeDestAccount)

        // Second payment that would push total to 10100 — over the 10000 limit
        val secondRequest = validRequest.copy(amount = MonetaryAmount("1100.00", "USD"))

        assertThatThrownBy { paymentService.createPayment(secondRequest, "key-d2b") }
            .isInstanceOf(PaymentDomainException::class.java)
            .satisfies({ ex ->
                assertThat((ex as PaymentDomainException).error)
                    .isInstanceOf(PaymentError.DailyLimitExceeded::class.java)
            })
    }
}

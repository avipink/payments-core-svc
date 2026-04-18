package com.digitalbank.payments.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.digitalbank.contracts.accounts.AccountResponse
import com.digitalbank.contracts.accounts.AccountStatus
import com.digitalbank.contracts.accounts.AccountType
import com.digitalbank.contracts.common.MonetaryAmount
import com.digitalbank.contracts.payments.PaymentRequest
import com.digitalbank.contracts.payments.PaymentType
import com.digitalbank.payments.client.AccountClient
import com.digitalbank.payments.exception.PaymentDomainException
import com.digitalbank.payments.repository.PaymentRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.slf4j.LoggerFactory

/**
 * Verifies that PaymentService audit log entries contain the required structured
 * fields and NEVER include PII values (balance amounts, holderName, payment amounts).
 *
 * PII constraint per service spec: log entries must contain only accountIds,
 * paymentIds, type, status, and error codes — not monetary values or holder names.
 */
@ExtendWith(MockitoExtension::class)
class AuditLogTest {

    @Mock
    private lateinit var accountClient: AccountClient

    private lateinit var paymentRepository: PaymentRepository
    private lateinit var paymentService: PaymentService
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var serviceLogger: Logger

    private val activeAccount = AccountResponse(
        accountId = "ACC-001",
        accountType = AccountType.CHECKING,
        holderName = "Alice Johnson",
        balance = MonetaryAmount("5000.00", "USD"),
        status = AccountStatus.ACTIVE,
        currency = "USD",
        lastTransactionDate = null,
        createdAt = "2025-01-01T00:00:00Z"
    )

    private val destAccount = AccountResponse(
        accountId = "ACC-002",
        accountType = AccountType.CHECKING,
        holderName = "Bob Smith",
        balance = MonetaryAmount("1000.00", "USD"),
        status = AccountStatus.ACTIVE,
        currency = "USD",
        lastTransactionDate = null,
        createdAt = "2025-01-01T00:00:00Z"
    )

    private val validRequest = PaymentRequest(
        fromAccountId = "ACC-001",
        toAccountId = "ACC-002",
        amount = MonetaryAmount("200.00", "USD"),
        type = PaymentType.INTERNAL_TRANSFER,
        reference = "ref-001"
    )

    @BeforeEach
    fun attachLogAppender() {
        paymentRepository = PaymentRepository()
        paymentService = PaymentService(paymentRepository, accountClient)

        serviceLogger = LoggerFactory.getLogger(PaymentService::class.java) as Logger
        listAppender = ListAppender<ILoggingEvent>().also { it.start() }
        serviceLogger.addAppender(listAppender)
    }

    @AfterEach
    fun detachLogAppender() {
        serviceLogger.detachAppender(listAppender)
    }

    // -------------------------------------------------------------------------
    // PII absence — success path
    // -------------------------------------------------------------------------

    @Test
    fun `success path - log does not contain balance amount`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(destAccount)

        paymentService.createPayment(validRequest, "log-key-1")

        val allMessages = listAppender.list.map { it.formattedMessage }
        allMessages.forEach { msg ->
            assertThat(msg)
                .withFailMessage("Log message contains balance amount '5000.00': %s", msg)
                .doesNotContain("5000.00")
            assertThat(msg)
                .withFailMessage("Log message contains destination balance '1000.00': %s", msg)
                .doesNotContain("1000.00")
        }
    }

    @Test
    fun `success path - log does not contain holderName`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(destAccount)

        paymentService.createPayment(validRequest, "log-key-2")

        val allMessages = listAppender.list.map { it.formattedMessage }
        allMessages.forEach { msg ->
            assertThat(msg)
                .withFailMessage("Log message contains holder name 'Alice Johnson': %s", msg)
                .doesNotContain("Alice Johnson")
            assertThat(msg)
                .withFailMessage("Log message contains holder name 'Bob Smith': %s", msg)
                .doesNotContain("Bob Smith")
        }
    }

    @Test
    fun `success path - log does not contain payment amount`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(destAccount)

        paymentService.createPayment(validRequest, "log-key-3")

        val allMessages = listAppender.list.map { it.formattedMessage }
        allMessages.forEach { msg ->
            assertThat(msg)
                .withFailMessage("Log message contains payment amount '200.00': %s", msg)
                .doesNotContain("200.00")
        }
    }

    // -------------------------------------------------------------------------
    // PII absence — failure paths
    // -------------------------------------------------------------------------

    @Test
    fun `insufficient funds failure - log does not contain balance or amount values`() {
        val lowBalance = activeAccount.copy(balance = MonetaryAmount("10.00", "USD"))
        given(accountClient.findAccount("ACC-001")).willReturn(lowBalance)

        assertThatThrownBy { paymentService.createPayment(validRequest, "log-key-4") }
            .isInstanceOf(PaymentDomainException::class.java)

        val allMessages = listAppender.list.map { it.formattedMessage }
        allMessages.forEach { msg ->
            assertThat(msg).doesNotContain("10.00")
            assertThat(msg).doesNotContain("200.00")
        }
    }

    @Test
    fun `validation failure - log does not contain request field values`() {
        val blankFromRequest = validRequest.copy(fromAccountId = "")

        assertThatThrownBy { paymentService.createPayment(blankFromRequest, "log-key-5") }
            .isInstanceOf(PaymentDomainException::class.java)

        val allMessages = listAppender.list.map { it.formattedMessage }
        allMessages.forEach { msg ->
            assertThat(msg).doesNotContain("200.00")
        }
    }

    // -------------------------------------------------------------------------
    // Required structured fields — success path
    // -------------------------------------------------------------------------

    @Test
    fun `success path - log contains operation field`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(destAccount)

        paymentService.createPayment(validRequest, "log-key-6")

        val allMessages = listAppender.list.map { it.formattedMessage }
        assertThat(allMessages).anyMatch { it.contains("operation=payment.create") }
    }

    @Test
    fun `success path - final log contains PENDING status and paymentId`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(destAccount)

        paymentService.createPayment(validRequest, "log-key-7")

        val allMessages = listAppender.list.map { it.formattedMessage }
        assertThat(allMessages).anyMatch { it.contains("status=PENDING") && it.contains("paymentId=") }
    }

    @Test
    fun `success path - log contains fromAccountId and toAccountId`() {
        given(accountClient.findAccount("ACC-001")).willReturn(activeAccount)
        given(accountClient.findAccount("ACC-002")).willReturn(destAccount)

        paymentService.createPayment(validRequest, "log-key-8")

        val allMessages = listAppender.list.map { it.formattedMessage }
        assertThat(allMessages).anyMatch { it.contains("ACC-001") && it.contains("ACC-002") }
    }

    // -------------------------------------------------------------------------
    // Required structured fields — failure paths
    // -------------------------------------------------------------------------

    @Test
    fun `account not found failure - log contains errorCode field`() {
        given(accountClient.findAccount("ACC-001")).willReturn(null)

        assertThatThrownBy { paymentService.createPayment(validRequest, "log-key-9") }
            .isInstanceOf(PaymentDomainException::class.java)

        val allMessages = listAppender.list.map { it.formattedMessage }
        assertThat(allMessages).anyMatch { it.contains("errorCode=PAYMENT_ACCOUNT_NOT_FOUND") }
    }

    @Test
    fun `currency mismatch failure - log contains errorCode CURRENCY_MISMATCH`() {
        val eurAccount = activeAccount.copy(
            balance = MonetaryAmount("5000.00", "EUR"),
            currency = "EUR"
        )
        given(accountClient.findAccount("ACC-001")).willReturn(eurAccount)

        assertThatThrownBy { paymentService.createPayment(validRequest, "log-key-10") }
            .isInstanceOf(PaymentDomainException::class.java)

        val allMessages = listAppender.list.map { it.formattedMessage }
        assertThat(allMessages).anyMatch { it.contains("errorCode=CURRENCY_MISMATCH") }
    }
}

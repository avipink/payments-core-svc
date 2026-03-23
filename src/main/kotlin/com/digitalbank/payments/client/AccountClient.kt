package com.digitalbank.payments.client

import com.digitalbank.contracts.accounts.AccountResponse
import com.digitalbank.contracts.accounts.AccountStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * HTTP client for accounts-core-svc.
 *
 * Calls GET /api/v1/accounts/{id} to validate that an account exists
 * and is in an operable state before processing a payment.
 *
 * Uses WebClient (non-blocking) with a synchronous block() at the service
 * boundary, keeping the rest of the service stack simple.
 */
@Component
class AccountClient(
    @Value("\${accounts-service.base-url}") baseUrl: String
) {
    private val log = LoggerFactory.getLogger(AccountClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(baseUrl)
        .build()

    /**
     * Returns the [AccountResponse] for [accountId], or null if the account
     * is not found (404) or the accounts service is unavailable.
     */
    fun findAccount(accountId: String): AccountResponse? {
        return try {
            webClient.get()
                .uri("/api/v1/accounts/{id}", accountId)
                .retrieve()
                .bodyToMono(AccountResponse::class.java)
                .block()
        } catch (ex: WebClientResponseException.NotFound) {
            log.debug("Account not found via accounts-svc: {}", accountId)
            null
        } catch (ex: Exception) {
            log.error("accounts-svc unavailable when fetching account {}: {}", accountId, ex.message)
            null
        }
    }

    /**
     * Returns true if the account exists and its status is ACTIVE.
     */
    fun isAccountActive(accountId: String): Boolean {
        val account = findAccount(accountId) ?: return false
        return account.status == AccountStatus.ACTIVE
    }
}

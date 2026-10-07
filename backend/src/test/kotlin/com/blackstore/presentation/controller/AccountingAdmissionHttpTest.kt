package com.blackstore.presentation.controller

import com.blackstore.domain.accounting.AccountingAdmissionException
import com.blackstore.domain.accounting.AccountingCommandFailure
import com.blackstore.infrastructure.exception.GlobalExceptionHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class AccountingAdmissionHttpTest {
    @Test fun `lifecycle denials have stable typed status and no successful payload`() {
        val handler = GlobalExceptionHandler()
        val request = MockHttpServletRequest()
        for ((failure, status) in listOf(
            AccountingCommandFailure.LegacyContractDisabled to 409,
            AccountingCommandFailure.Paused to 409,
            AccountingCommandFailure.NotActivated to 409,
            AccountingCommandFailure.Unavailable to 503,
        )) {
            val response = handler.handleAccountingAdmission(AccountingAdmissionException(failure), request)
            assertEquals(status, response.statusCode.value())
            assertEquals("no-store", response.headers.cacheControl)
            assertEquals(failure.wire, response.body?.errorCode)
            assertNull(response.body?.data)
            assertEquals(false, response.body?.retryable)
            assertEquals("Accounting operation unavailable or denied", response.body?.message)
        }
    }
}

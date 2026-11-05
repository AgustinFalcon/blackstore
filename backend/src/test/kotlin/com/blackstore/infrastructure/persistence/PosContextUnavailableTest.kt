package com.blackstore.infrastructure.persistence

import com.blackstore.domain.cash.StaffRole
import com.blackstore.domain.identity.*
import com.blackstore.domain.pos.PosContextResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.sql.SQLException
import javax.sql.DataSource

class PosContextUnavailableTest {
    @Test fun databaseUnavailableProducesNeutralContextWithoutFallback() {
        val source=Mockito.mock(DataSource::class.java)
        Mockito.`when`(source.connection).thenThrow(SQLException("private database detail"))
        assertEquals(PosContextResult.Unavailable,JdbcPosContextQuery(source,1,"11111111-1111-4111-8111-111111111111")
            .observe(AuthenticatedStaff(StaffUserId(1),"Cashier",StaffRole.CASHIER)))
    }
}

package com.blackstore.infrastructure.persistence

import com.blackstore.domain.port.out.workspace.WorkspaceQuery
import com.blackstore.domain.port.out.workspace.WorkspaceSnapshot
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["blackstore.persistence.enabled"], havingValue = "true")
class JdbcWorkspaceQuery(
    private val seed: LocalDatabaseSeed,
) : WorkspaceQuery {
    override fun current() =
        WorkspaceSnapshot(
            terminalId = seed.terminalId,
            cashierId = seed.cashierId,
            persistence = "postgresql",
        )
}

package com.blackstore.infrastructure.workspace

import com.blackstore.domain.port.out.workspace.WorkspaceQuery
import com.blackstore.domain.port.out.workspace.WorkspaceSnapshot
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    name = ["blackstore.persistence.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryWorkspaceQuery : WorkspaceQuery {
    override fun current() = WorkspaceSnapshot(terminalId = 10, cashierId = 7, persistence = "memory")
}

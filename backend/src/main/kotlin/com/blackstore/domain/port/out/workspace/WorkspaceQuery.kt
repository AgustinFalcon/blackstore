package com.blackstore.domain.port.out.workspace

interface WorkspaceQuery {
    fun current(): WorkspaceSnapshot
}

data class WorkspaceSnapshot(
    val terminalId: Long,
    val cashierId: Long,
    val persistence: String,
)

package com.blackstore.domain.port.out.companion

import com.blackstore.domain.companion.CompanionInstallation

interface LoadCompanionInstallationPort {
    fun loadSingleton(): CompanionInstallation?

    fun count(): Int
}

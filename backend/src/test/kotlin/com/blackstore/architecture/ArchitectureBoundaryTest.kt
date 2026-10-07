package com.blackstore.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class ArchitectureBoundaryTest {

    private val imported = ClassFileImporter().importPackages("com.blackstore")

    @Test
    fun domainMustNotDependOnSpringOrJpa() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "javax.persistence..",
                "org.hibernate..",
            )
            .check(imported)
    }

    @Test
    fun applicationMustNotDependOnInfrastructure() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(imported)
    }

    @Test
    fun accountingDomainHasNoDatabaseHttpOrFrameworkTypes() {
        noClasses()
            .that().resideInAnyPackage("..domain.accounting..", "..domain.reports..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "java.sql..", "javax.sql..", "java.net.http..",
                "jakarta.persistence..", "com.fasterxml.jackson..",
            )
            .check(imported)
    }

    @Test
    fun presentationMustNotDependOnInfrastructurePersistence() {
        noClasses()
            .that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..")
            .check(imported)
    }

    @Test
    fun storeCoreContractBoundaryHasNoHttpOrFrameworkTypes() {
        noClasses()
            .that()
            .resideInAnyPackage(
                "..domain.port.out.storecore..",
                "..application.port.out.storecore..",
                "..application.storecore..",
                "..application.dto.storecore..",
                "..domain.model..",
            )
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.http..",
                "org.springframework.web..",
                "java.net.http..",
                "okhttp3..",
                "org.springframework.jdbc..",
            )
            .check(imported)
    }

    @Test
    fun storeCorePortsLiveInDomainOnly() {
        val portClasses =
            imported
                .filter { it.simpleName.endsWith("Port") && it.packageName.contains("storecore") }
        assertTrue(portClasses.isNotEmpty(), "Expected StoreCore port interfaces in domain")
        portClasses.forEach { clazz ->
            assertTrue(
                clazz.packageName.contains("domain.port.out"),
                "${clazz.name} must live under domain.port.out",
            )
        }
    }

    @Test
    fun resourcesMustNotReferenceStoreCoreDatabase() {
        val resources = Path.of("src/main/resources")
        if (!Files.exists(resources)) return
        Files.walk(resources).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val content = file.readText()
                    assertFalse(
                        STORE_CORE_DB_PATTERN.containsMatchIn(content),
                        "Forbidden StoreCore DB reference in ${file.fileName}",
                    )
                }
        }
    }

    @Test
    fun buildMustNotDeclareStoreCoreJdbcDriver() {
        val gradle = Path.of("build.gradle.kts").readText()
        assertFalse(
            gradle.contains("storecore", ignoreCase = true),
            "build.gradle.kts must not reference StoreCore artifacts",
        )
        assertFalse(
            JDBC_DRIVER_PATTERN.containsMatchIn(gradle),
            "No secondary JDBC driver for StoreCore database",
        )
    }

    companion object {
        private val STORE_CORE_DB_PATTERN = Regex("""jdbc:postgresql://.*storecore""", RegexOption.IGNORE_CASE)
        private val JDBC_DRIVER_PATTERN = Regex("""postgresql.*storecore|storecore.*postgresql""", RegexOption.IGNORE_CASE)
    }
}

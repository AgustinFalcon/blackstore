package com.blackstore.application.sales

import com.blackstore.application.dto.sales.*
import com.blackstore.domain.sales.*
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class SaleAdmissionV2ContractTest {
    private val mapper = jacksonObjectMapper()
    private val codec = SaleAdmissionV2Fingerprint()
    private fun request() = SaleReserveV3Request(UUID.fromString("00000000-0000-0000-0000-000000000003"),
        "00000000-0000-0000-0000-000000000001", "device", "sale", "00000000-0000-0000-0000-000000000002", 1,
        listOf(SaleReserveV3LineRequest("a", IntNode(2), "P1", "SKU-A", "Artículo A", BigDecimal("10.00"), BigDecimal.ZERO),
            SaleReserveV3LineRequest("b", IntNode(1), "P2", "SKU-B", "Artículo B", BigDecimal("7.50"), BigDecimal.ZERO)))
    private fun command(r: SaleReserveV3Request = request()) = SaleV3RequestTranslator.reserve(r)

    @Test fun independentlyCalculatedGoldenAndPermutationPreserveEvidenceOrder() {
        val r = request()
        val original = command(r)
        assertEquals("17:sale-admission-v27:Reserve1:71:136:00000000-0000-0000-0000-0000000000016:device4:sale36:00000000-0000-0000-0000-0000000000020:1:21:a1:22:P15:SKU-A11:Artículo A5:10.004:0.001:b1:12:P25:SKU-B11:Artículo B4:7.504:0.00", codec.canonical(7, 1, original))
        assertEquals("fbfb0ce9bb08724799a906d71bd218e1c4112fab0c25f7c7b2ef04299b422cab", codec.hash(7, 1, original))
        val reversed = command(r.copy(lines = r.lines.reversed(), commandId = UUID.randomUUID()))
        assertEquals(codec.hash(7, 1, original), codec.hash(7, 1, reversed))
        assertEquals(listOf("b", "a"), reversed.lines.map { it.variantId })
        assertNotEquals(codec.hash(7, 1, original), codec.hash(8, 1, original))
        assertNotEquals(codec.hash(7, 1, original), codec.hash(7, 1, command(r.copy(reason = "reason"))))
        assertNotEquals(codec.hash(7, 1, original), codec.hash(7, 1, command(r.copy(lines = r.lines.map { it.copy(quantity = IntNode(3)) }))))
    }

    @Test fun rawInvalidUnicodeRejectedInEveryHashedTextBeforeNormalization() {
        val invalid = listOf("\u0000", "\uD800", "\uDC00", "\uD800x", "\uDC00\uD800")
        for (bad in invalid) {
            val r = request()
            val mutations = listOf(r.copy(clientInstanceId = bad), r.copy(deviceId = bad), r.copy(saleId = bad),
                r.copy(operationId = bad), r.copy(reason = " $bad ")) +
                listOf<(SaleReserveV3LineRequest) -> SaleReserveV3LineRequest>(
                    { it.copy(variantId = bad) }, { it.copy(expectedPriceVersion = bad) },
                    { it.copy(sku = bad) }, { it.copy(productName = " $bad ") }
                ).map { mutate -> r.copy(lines = listOf(mutate(r.lines.first()))) }
            mutations.forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { command(candidate) } }
        }
    }

    @Test fun trimSetIsExactAndUnicodeWhitespaceRemainsSemantic() {
        for (edge in listOf('\u0009','\u000A','\u000B','\u000C','\u000D','\u0020')) {
            assertEquals("value", SaleTextV2.trim("${edge}value$edge"))
            assertThrows(IllegalArgumentException::class.java) { SaleTextV2.ref("${edge}value") }
            assertThrows(IllegalArgumentException::class.java) { SaleTextV2.reason(edge.toString()) }
        }
        for (edge in listOf('\u00A0', '\u0085', '\u2003', '\uFEFF')) {
            assertEquals("${edge}value$edge", SaleTextV2.name("${edge}value$edge"))
            val r = request()
            assertEquals(edge.toString(), command(r.copy(lines = listOf(r.lines.first().copy(productName = edge.toString())))).lines.single().ticketLine.productName)
        }
        assertEquals("a\t b", SaleTextV2.trim(" a\t b "))
        assertEquals(null, SaleTextV2.reason(null))
        assertNotEquals(codec.hash(7, 1, command(request().copy(reason = "é"))), codec.hash(7, 1, command(request().copy(reason = "e\u0301"))))
        assertTrue(codec.canonical(7, 1, command(request().copy(reason = "😀"))).contains("4:😀"))
        val legacy = request()
        assertThrows(IllegalArgumentException::class.java) {
            SaleV2RequestTranslator.reserve(SaleReserveV2Request(legacy.commandId, legacy.clientInstanceId, legacy.deviceId, legacy.saleId,
                legacy.operationId, 1, "a", 1, "P1", "SKU-A", "\u00A0\u2003", BigDecimal.TEN))
        }
    }

    @Test fun utf8OrderIsNotUtf16OrLocaleOrder() {
        val r = request()
        val c = command(r.copy(lines = listOf(r.lines[0].copy(variantId = "😀"), r.lines[1].copy(variantId = "\uE000"))))
        val canonical = codec.canonical(7, 1, c)
        assertTrue(canonical.indexOf("3:\uE000") < canonical.indexOf("4:😀"))
    }

    @Test fun decoderRejectsCoercionAndNestedUnknownFields() {
        val base = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(request())
        for (raw in listOf("1.5", "\"1\"", "true", "null", "2147483648", "0", "-1")) {
            val node = base.deepCopy()
            (node["lines"][0] as com.fasterxml.jackson.databind.node.ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>("quantity", mapper.readTree(raw))
            assertThrows(Exception::class.java) { command(mapper.readValue(mapper.writeValueAsString(node))) }
        }
        for (nested in listOf(false, true)) {
            val node = base.deepCopy()
            val target = if (nested) node["lines"][0] as com.fasterxml.jackson.databind.node.ObjectNode else node
            target.putNull("actorId")
            assertThrows(Exception::class.java) { mapper.readValue<SaleReserveV3Request>(mapper.writeValueAsString(node)) }
        }
    }

    @Test fun collectionAndMoneyLimitsAreCheckedAsWholeSale() {
        val r = request()
        assertThrows(IllegalArgumentException::class.java) { command(r.copy(lines = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { command(r.copy(lines = List(101) { r.lines.first().copy(variantId = "v$it") })) }
        assertThrows(IllegalArgumentException::class.java) { command(r.copy(lines = listOf(r.lines.first(), r.lines.first()))) }
        assertThrows(IllegalArgumentException::class.java) { command(r.copy(lines = r.lines.map { it.copy(quantity = IntNode(1), originalUnitPrice = BigDecimal("999999999999.99")) })) }
        assertThrows(IllegalArgumentException::class.java) { command(r.copy(lines = listOf(r.lines.first().copy(discountAmount = BigDecimal.ONE)))) }
        assertEquals(100, command(r.copy(lines = List(100) { r.lines.first().copy(variantId = "v$it") })).lines.size)
    }

    @Test fun contractTranslatorsFailClosed() {
        assertEquals(SaleFingerprintVersion.Unknown, SaleFingerprintVersion.fromWire("future"))
        assertEquals(SaleAdmissionFamily.Unknown, SaleAdmissionFamily.fromWire(null))
        assertEquals(SaleAdmissionRoute.Unknown, SaleAdmissionRoute.fromWire("https://other/reservations"))
        assertEquals(SaleFingerprintVersion.AdmissionV2, SaleAdmissionRoute.ReserveV3.fingerprintVersion)
        assertEquals(SaleFingerprintVersion.AdmissionV1, SaleAdmissionRoute.CommitV2.fingerprintVersion)
    }

    @Test fun v1ReserveCommitAndReleaseGoldenHashesStayUnchanged() {
        val c = command()
        val line = c.lines.first()
        val reserve = SaleCommand.Reserve(c.commandId, c.identity, 1, line.variantId, line.quantity, line.expectedPriceVersion, line.ticketLine, null)
        val v1 = SaleCommandFingerprint()
        assertEquals("aa01960c783d27c817903495a4803b3fc803ef0621642c1f964c53eac8356722", v1.hash(7, 1, reserve))
        assertEquals("b1b70111889fe9c110a60c8ceec5fa300b7f4a5680d29a73c45f1fe91665b9bc", v1.hash(7, 1, SaleCommand.Commit(c.commandId, c.identity, null)))
        assertEquals("e8f8a2f79c5fac28fbe45553f32d6f4e97b63e4838dc8e579c2f5f313f17f735", v1.hash(7, 1, SaleCommand.Release(c.commandId, c.identity, null)))
        assertThrows(IllegalArgumentException::class.java) { v1.hash(7, 1, c) }
    }
}

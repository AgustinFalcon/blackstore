package com.blackstore.connector

import com.blackstore.application.dto.storecore.StoreCoreReceiptDto
import com.blackstore.application.storecore.StoreCoreEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object StoreCoreContractFixtures {
    private val mapper =
        jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun text(name: String): String =
        requireNotNull(javaClass.getResource("/storecore-fixtures/$name.json")).readText()

    fun pin(): JsonNode = mapper.readTree(text("pin"))

    fun envelope(name: String): StoreCoreEnvelope<StoreCoreReceiptDto?> {
        val root = mapper.readTree(text(name))
        val errorCode = root.path("errorCode").takeUnless { it.isNull || it.isMissingNode }?.asText()
        val data =
            if (errorCode == null && root.path("data").isObject && root.path("data").has("state")) {
                mapper.convertValue(root.path("data"), StoreCoreReceiptDto::class.java)
            } else {
                null
            }
        return StoreCoreEnvelope(
            code = root.path("code").asInt(),
            data = data,
            errorCode = errorCode,
            retryable = if (root.path("retryable").isBoolean) root.path("retryable").asBoolean() else null,
            message = root.path("message").takeUnless { it.isNull || it.isMissingNode }?.asText(),
            traceId = root.path("traceId").asText(),
        )
    }
}

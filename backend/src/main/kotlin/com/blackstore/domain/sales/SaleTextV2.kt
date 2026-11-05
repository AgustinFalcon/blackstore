package com.blackstore.domain.sales

/** AdmissionV2 text policy. Never use platform whitespace rules or replacement encoding. */
object SaleTextV2 {
    fun validate(raw: String): String {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            require(c != '\u0000') { "NUL is not representable in durable text" }
            when {
                c.isHighSurrogate() -> {
                    require(i + 1 < raw.length && raw[i + 1].isLowSurrogate()) { "invalid Unicode scalar sequence" }
                    i += 2
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("invalid Unicode scalar sequence")
                else -> i++
            }
        }
        return raw
    }
    private fun edge(c: Char) = c == '\u0020' || c in '\u0009'..'\u000D'
    fun trim(raw: String): String {
        validate(raw)
        var start = 0
        var end = raw.length
        while (start < end && edge(raw[start])) start++
        while (end > start && edge(raw[end - 1])) end--
        return raw.substring(start, end)
    }
    fun ref(raw: String): String {
        validate(raw)
        require(raw.isNotEmpty() && raw.length <= 80 && raw == trim(raw))
        return raw
    }
    fun name(raw: String): String = trim(raw).also { require(it.isNotEmpty() && it.length <= 250) }
    fun reason(raw: String?): String? = raw?.let { trim(it).also { value -> require(value.isNotEmpty() && value.length <= 500) } }
}

package com.blackstore.domain.sales

enum class SaleAdmissionFamily(val wire: String) {
    V2("sale-v2"), V3("sale-v3"), Unknown("unknown");
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.wire == raw } ?: Unknown }
}

enum class SaleFingerprintVersion(val wire: String) {
    AdmissionV1("sale-admission-v1"), AdmissionV2("sale-admission-v2"), Unknown("unknown");
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.wire == raw } ?: Unknown }
}

enum class SaleAdmissionRoute(val path: String, val family: SaleAdmissionFamily,
    val fingerprintVersion: SaleFingerprintVersion, val kind: SaleCommandKind) {
    ReserveV2("/api/v2/sales/reservations", SaleAdmissionFamily.V2, SaleFingerprintVersion.AdmissionV1, SaleCommandKind.Reserve),
    ReserveV3("/api/v3/sales/reservations", SaleAdmissionFamily.V3, SaleFingerprintVersion.AdmissionV2, SaleCommandKind.Reserve),
    CommitV2("/api/v2/sales/{operationId}/commit", SaleAdmissionFamily.V2, SaleFingerprintVersion.AdmissionV1, SaleCommandKind.Commit),
    ReleaseV2("/api/v2/sales/{operationId}/release", SaleAdmissionFamily.V2, SaleFingerprintVersion.AdmissionV1, SaleCommandKind.Release),
    Unknown("", SaleAdmissionFamily.Unknown, SaleFingerprintVersion.Unknown, SaleCommandKind.Unknown);
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.path == raw && it != Unknown } ?: Unknown }
}

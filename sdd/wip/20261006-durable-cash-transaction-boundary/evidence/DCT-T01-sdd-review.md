# DCT-T01 — revisión del diseño

Fecha: 2026-10-06. Base: `e6a47a32b5f21b9f020fc1f9f23a73a19e5ed4a7`.
Reviewer: GPT-6.1 Sol, razonamiento medium, agente `/root/blackstore_cash_sdd_review`.
Resultado informado por el coordinador: `APPROVED`, sin P0–P3, sobre el SDD corregido después de R1.

Las correcciones aceptadas separan visibilidad SID de elegibilidad durable para preservar 404 indistinguible y permitir 409 sólo a caja visible. También conservan la condición SQL `OPEN` y rowcount uno ya presentes; la brecha corregida es la transacción/auditoría y la autorización fuera del lock.

Durante implementación se observó que V4 ya impone `uq_open_cash_session_per_cashier`, además del índice de terminal V1. El coordinador autorizó explícitamente reconocer ambos nombres junto a SQLSTATE `23505`, con el mismo rollback y filtro de visibilidad, y pidió dejarlo documentado y probado. No se amplían grants ni se crea migración. Esta evidencia aprueba diseño/implementación acotada; no sustituye revisión final de código, crash, browser ni CI.

# TASK-ADP-004 — Envelope y DTO

**Fecha:** 2026-09-23  
**Límite:** validación de `BaseResponse` y mapper DTO→dominio. Sin cliente HTTP, secretos ni host live.

## Completitud

`StoreCoreEnvelopeValidator` exige los seis campos, `HTTP status == code`, éxito 200 con data y error fields null, error con data null + `errorCode`/`retryable`/`message`/`traceId`. `OPERATION_RETIRED` fuerza `retryable=false`.

## Branching

`StoreCoreRecoveryPolicy.actionForError` usa sólo `errorCode`. El test compara dos mensajes distintos del mismo código y obtiene `NEVER_REPOST`.

## Mapper

`StoreCoreReceiptDto.toDomain` no importa tipos HTTP/Spring web. Digest canónico `7b907a2e…de30`.

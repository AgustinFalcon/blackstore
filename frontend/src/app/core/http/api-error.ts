import { HttpErrorResponse } from '@angular/common/http';
import { BaseResponse } from '../models/base-response';

export interface ApiFailure {
  readonly errorCode: string;
  readonly retryable: boolean;
  readonly traceId: string | null;
  readonly httpStatus: number;
  readonly availableQuantity: number | null;
}

export type LoadResult<T> = { readonly ok: true; readonly data: T } | { readonly ok: false; readonly failure: ApiFailure };

export function readApiFailure(error: unknown): ApiFailure {
  if (error instanceof HttpErrorResponse) {
    return failureFromUnknown(error.error, error.status);
  }
  return { errorCode: 'UNKNOWN', retryable: true, traceId: null, httpStatus: 0, availableQuantity: null };
}

export function failureFromBody(response: BaseResponse<unknown>): ApiFailure {
  return {
    errorCode: response.errorCode ?? 'UNKNOWN',
    retryable: response.retryable === true,
    traceId: response.traceId,
    httpStatus: response.code,
    availableQuantity: quantityFrom(response.data),
  };
}

export function copyForErrorCode(errorCode: string): string {
  switch (errorCode) {
    case 'CAPABILITY_DISABLED':
      return 'El puesto no está habilitado. No se puede escribir.';
    case 'FORBIDDEN':
      return 'Rol insuficiente.';
    case 'VALIDATION':
      return 'Revisá los datos del formulario.';
    case 'INSUFFICIENT_STOCK':
      return 'Sin stock vendible.';
    case 'CATALOG_VERSION_STALE':
      return 'Catálogo vencido.';
    case 'CONFLICT':
      return 'Conflicto de la misma operación. Reintentá.';
    case 'OPERATION_STATE_CONFLICT':
      return 'El estado de la operación cambió. Consultá de nuevo.';
    case 'OPERATION_RETIRED':
      return 'La operación quedó retirada. No se reenvía.';
    case 'NOT_FOUND':
      return 'No encontramos esa venta en este proceso.';
    case 'RATE_LIMITED':
      return 'Demasiados intentos. Esperá y reintentá.';
    case 'INTEGRATION_BLOCKED':
      return 'Integración StoreCore bloqueada — simulador local.';
    default:
      return 'No se pudo completar. Reintentá.';
  }
}

function failureFromUnknown(body: unknown, httpStatus: number): ApiFailure {
  if (!body || typeof body !== 'object') {
    return { errorCode: 'UNKNOWN', retryable: httpStatus === 0 || httpStatus >= 500, traceId: null, httpStatus, availableQuantity: null };
  }
  const record = body as Partial<BaseResponse<unknown>>;
  return {
    errorCode: typeof record.errorCode === 'string' && record.errorCode ? record.errorCode : 'UNKNOWN',
    retryable: record.retryable === true,
    traceId: typeof record.traceId === 'string' ? record.traceId : null,
    httpStatus,
    availableQuantity: quantityFrom(record.data),
  };
}

function quantityFrom(data: unknown): number | null {
  if (!data || typeof data !== 'object' || !('availableQuantity' in data)) return null;
  const value = (data as { availableQuantity?: unknown }).availableQuantity;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

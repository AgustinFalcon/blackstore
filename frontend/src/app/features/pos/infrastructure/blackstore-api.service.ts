import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { LoadResult, failureFromBody, readApiFailure } from '../../../core/http/api-error';
import { BaseResponse, isSuccessResponse } from '../../../core/models/base-response';
import { asNumber, asNumberOrNull } from '../domain/money';
import { CashSessionStatus, PaymentStatus, PosPaymentMethod, ReportFormula, ReportPeriod, SaleStatus } from '../domain/closed-status';
import { CashSession, CatalogItem, CatalogSnapshot, ShiftReport, WorkspaceSnapshot } from '../domain/pos.models';

export interface ReserveSaleBody {
  readonly clientInstanceId: string;
  readonly deviceId: string;
  readonly saleId: string;
  readonly operationId: string;
  readonly cashSessionId: number;
  readonly variantId: string;
  readonly quantity: number;
  readonly expectedPriceVersion: string;
  readonly sku: string;
  readonly productName: string;
  readonly originalUnitPrice: number;
  readonly discountAmount: number;
}

export interface SaleStatusData {
  readonly operationId: string;
  readonly status: SaleStatus;
  readonly receipt: string | null;
  readonly reservationRef: string | null;
}

export interface PaymentData {
  readonly id: number;
  readonly status: PaymentStatus;
  readonly amount: number;
  readonly feeAmount: number;
}

@Injectable({ providedIn: 'root' })
export class BlackstoreApiService {
  private readonly http = inject(HttpClient);

  workspace(): Observable<LoadResult<WorkspaceSnapshot>> {
    return this.execute(this.http.get<BaseResponse<WorkspaceSnapshot>>(`${API_BASE}/workspace`), (data) => ({
      terminalId: asNumber(data.terminalId),
      cashierId: asNumber(data.cashierId),
      persistence: data.persistence,
    }));
  }

  catalog(): Observable<LoadResult<CatalogSnapshot>> {
    return this.execute(
      this.http.get<BaseResponse<CatalogSnapshot>>(`${API_BASE}/catalog`),
      (data) => ({
        version: data.version,
        stale: data.stale,
        importedAt: data.importedAt,
        validUntil: data.validUntil,
        items: (data.items ?? []).map(toItem),
      }),
    );
  }

  cashSessions(): Observable<LoadResult<readonly CashSession[]>> {
    return this.execute(this.http.get<BaseResponse<CashSession[]>>(`${API_BASE}/cash-sessions`), (rows) => rows.map(toSession));
  }

  openSession(body: { readonly terminalId: number; readonly cashierId: number; readonly openingCash: number }): Observable<LoadResult<CashSession>> {
    return this.execute(this.http.post<BaseResponse<CashSession>>(`${API_BASE}/cash-sessions`, body), toSession);
  }

  closeSession(sessionId: number, body: { readonly declared: number; readonly reason: string }): Observable<LoadResult<CashSession>> {
    return this.execute(this.http.post<BaseResponse<CashSession>>(`${API_BASE}/cash-sessions/${sessionId}/close`, body), toSession);
  }

  expense(body: {
    readonly cashSessionId: number;
    readonly category: string;
    readonly amount: number;
    readonly reason: string;
    readonly method: PosPaymentMethod;
  }): Observable<LoadResult<{ readonly id: number; readonly amount: number; readonly category: string }>> {
    return this.execute(
      this.http.post<BaseResponse<{ id: number; amount: number; category: string }>>(`${API_BASE}/expenses`, {
        ...body,
        method: body.method.code,
      }),
      (data) => ({ id: asNumber(data.id), amount: asNumber(data.amount), category: data.category }),
    );
  }

  reserve(body: ReserveSaleBody): Observable<LoadResult<SaleStatusData>> {
    return this.execute(this.http.post<BaseResponse<SaleStatusData>>(`${API_BASE}/sales/reservations`, body), toStatus);
  }

  sale(operationId: string): Observable<LoadResult<SaleStatusData>> {
    return this.execute(this.http.get<BaseResponse<SaleStatusData>>(`${API_BASE}/sales/${operationId}`), toStatus);
  }

  commit(operationId: string): Observable<LoadResult<SaleStatusData>> {
    return this.execute(this.http.post<BaseResponse<SaleStatusData>>(`${API_BASE}/sales/${operationId}/commit`, {}), toStatus);
  }

  release(operationId: string): Observable<LoadResult<SaleStatusData>> {
    return this.execute(this.http.post<BaseResponse<SaleStatusData>>(`${API_BASE}/sales/${operationId}/release`, {}), toStatus);
  }

  pay(body: {
    readonly operationId: string;
    readonly method: PosPaymentMethod;
    readonly amount: number;
    readonly feeAmount: number;
  }): Observable<LoadResult<PaymentData>> {
    return this.execute(
      this.http.post<BaseResponse<PaymentData>>(`${API_BASE}/payments`, { ...body, method: body.method.code }),
      toPayment,
    );
  }

  reverse(paymentId: number, body: { readonly operationId: string; readonly reason: string; readonly evidenceRef: string }): Observable<LoadResult<PaymentData>> {
    return this.execute(this.http.post<BaseResponse<PaymentData>>(`${API_BASE}/payments/${paymentId}/reversals`, body), toPayment);
  }

  shiftReport(): Observable<LoadResult<ShiftReport>> {
    return this.execute(this.http.get<BaseResponse<ShiftReport>>(`${API_BASE}/reports/shift`), toReport);
  }

  dailyReport(): Observable<LoadResult<ShiftReport>> {
    return this.execute(this.http.get<BaseResponse<ShiftReport>>(`${API_BASE}/reports/daily`), toReport);
  }

  private execute<TRaw, TOut>(request: Observable<BaseResponse<TRaw>>, project: (data: TRaw) => TOut): Observable<LoadResult<TOut>> {
    return request.pipe(
      map((response) => {
        if (!isSuccessResponse(response)) return { ok: false as const, failure: failureFromBody(response) };
        return { ok: true as const, data: project(response.data) };
      }),
      catchError((error: unknown) => of({ ok: false as const, failure: readApiFailure(error) })),
    );
  }
}

function toItem(item: CatalogItem): CatalogItem {
  return {
    sku: item.sku,
    name: item.name,
    variantId: item.variantId,
    priceVersion: item.priceVersion ?? null,
    unitPrice: asNumberOrNull(item.unitPrice),
  };
}

function toSession(session: CashSession): CashSession {
  return {
    id: asNumber(session.id),
    terminalId: asNumber(session.terminalId),
    cashierId: asNumber(session.cashierId),
    status: CashSessionStatus.fromWire(session.status),
    openingCash: asNumber(session.openingCash),
    closingCashDeclared: asNumberOrNull(session.closingCashDeclared),
  };
}

function toStatus(data: SaleStatusData): SaleStatusData {
  return {
    operationId: data.operationId,
    status: SaleStatus.fromWire(data.status),
    receipt: data.receipt,
    reservationRef: data.reservationRef,
  };
}

function toPayment(data: PaymentData): PaymentData {
  return {
    id: asNumber(data.id),
    status: PaymentStatus.fromWire(data.status),
    amount: asNumber(data.amount),
    feeAmount: asNumber(data.feeAmount),
  };
}

function toReport(data: ShiftReport): ShiftReport {
  return {
    grossSales: asNumber(data.grossSales),
    discounts: asNumber(data.discounts),
    netSales: asNumber(data.netSales),
    refunds: asNumber(data.refunds),
    collected: asNumber(data.collected),
    feesPaid: asNumber(data.feesPaid),
    expensesPaid: asNumber(data.expensesPaid),
    operatingCashFlow: asNumber(data.operatingCashFlow),
    margin: asNumberOrNull(data.margin),
    formulaName: ReportFormula.fromWire(data.formulaName),
    fiscalResult: data.fiscalResult,
    periodKind: ReportPeriod.fromWire(data.periodKind),
  };
}

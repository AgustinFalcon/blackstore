import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingReport, AccountingReportRequest, ReportFailure } from '../domain/accounting-report';
import { ReportPeriod } from '../domain/pos-types';
import { StaffPermission } from '../domain/session-types';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { SessionStore } from './session.store';

/** Each period owns its cancellation and epoch; one failed read cannot erase the other. */
export class ReportReadSlot {
  readonly report = signal<AccountingReport | null>(null);
  readonly error = signal<ReportFailure | null>(null);
  readonly loading = signal(false);
  private epoch = 0;
  private pending: Subscription | null = null;
  constructor(private readonly http: HttpClient, private readonly identity: SessionStore, private readonly period: ReportPeriod,
    private readonly permission: StaffPermission, private readonly path: string) {}
  clear(): void {
    this.epoch++; this.pending?.unsubscribe(); this.pending = null;
    this.report.set(null); this.error.set(null); this.loading.set(false);
  }
  load(request: AccountingReportRequest | null): void {
    this.clear();
    if (!this.identity.can(this.permission)) { this.error.set(ReportFailure.Forbidden); return; }
    if (!request || request.period !== this.period) { this.error.set(ReportFailure.Validation); return; }
    const generation = this.identity.generation();
    const epoch = this.epoch;
    const current = () => generation === this.identity.generation() && epoch === this.epoch && this.identity.can(this.permission);
    this.loading.set(true);
    this.pending = this.http.get<unknown>(`${ACCOUNTING_API_BASE}/reports/${this.path}`, { params: request.params }).subscribe({
      next: response => {
        if (!current()) return;
        const report = PosWireMapper.accountingReport(response, request);
        this.report.set(report); this.error.set(report ? null : ReportFailure.Unknown); this.loading.set(false);
      },
      error: error => {
        if (!current()) return;
        this.error.set(PosWireMapper.reportFailure(error instanceof HttpErrorResponse ? error.status : null)); this.loading.set(false);
      },
      complete: () => { if (current()) this.loading.set(false); },
    });
  }
}

@Injectable()
export class AccountingReportsStore {
  private readonly http = inject(HttpClient);
  private readonly identity = inject(SessionStore);
  readonly shift = new ReportReadSlot(this.http, this.identity, ReportPeriod.Shift, StaffPermission.ShiftReportRead, 'shift');
  readonly day = new ReportReadSlot(this.http, this.identity, ReportPeriod.Day, StaffPermission.DailyReportRead, 'day');
  constructor() { this.identity.changed.pipe(takeUntilDestroyed()).subscribe(() => this.clear()); }
  clear(): void { this.shift.clear(); this.day.clear(); }
}

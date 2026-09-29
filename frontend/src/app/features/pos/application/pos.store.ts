import { Injectable, computed, signal } from '@angular/core';
import { PaymentStatus, PosPaymentMethod, SaleStatus } from '../domain/closed-status';
import {
  CashSession,
  CatalogSnapshot,
  ExpenseLog,
  RecordedPayment,
  SaleSnapshot,
  ShiftReport,
  TicketPrompt,
  WorkspaceSnapshot,
} from '../domain/pos.models';
import { catalogIsClosed, newSaleBlockReason } from '../domain/sale-block';

const SALES_KEY = 'blackstore.pos.sales';
const EXPENSES_KEY = 'blackstore.pos.expenses';

@Injectable({ providedIn: 'root' })
export class PosStore {
  readonly healthLoading = signal(true);
  readonly backendDown = signal(false);
  readonly integrationBlocked = signal(true);
  readonly writesDisabled = signal(false);
  readonly killPrompt = signal(false);

  readonly counterLoading = signal(false);
  readonly catalog = signal<CatalogSnapshot | null>(null);
  readonly catalogUnavailable = signal(false);
  readonly catalogError = signal<string | null>(null);
  readonly sessions = signal<readonly CashSession[]>([]);
  readonly sessionsError = signal<string | null>(null);
  readonly workspace = signal<WorkspaceSnapshot | null>(null);

  readonly cashBusy = signal(false);
  readonly cashError = signal<string | null>(null);
  readonly cashNotice = signal<string | null>(null);
  readonly expenseLog = signal<readonly ExpenseLog[]>(readJson<ExpenseLog[]>(EXPENSES_KEY, []));

  readonly salesById = signal<Readonly<Record<string, SaleSnapshot>>>(readSales());
  readonly activeSale = signal<SaleSnapshot | null>(null);
  readonly stockBlocked = signal(false);
  readonly ticketPrompt = signal<TicketPrompt | null>(null);
  readonly ticketBusy = signal(false);
  readonly ticketError = signal<string | null>(null);
  readonly ticketMessage = signal<string | null>(null);

  readonly reportsLoading = signal(false);
  readonly reportsError = signal<string | null>(null);
  readonly dailyError = signal<string | null>(null);
  readonly shiftReport = signal<ShiftReport | null>(null);
  readonly dailyReport = signal<ShiftReport | null>(null);

  readonly readLoading = signal(false);
  readonly readError = signal<string | null>(null);
  readonly readSale = signal<SaleSnapshot | null>(null);
  readonly readMissing = signal(false);

  private promptToken = 0;

  readonly openSession = computed(() => this.sessions().find((session) => session.status.open) ?? null);
  readonly catalogClosed = computed(() => catalogIsClosed(this.catalog(), this.catalogUnavailable()));
  readonly freshSaleBlock = computed(() =>
    newSaleBlockReason({
      loading: this.counterLoading(),
      catalog: this.catalog(),
      catalogUnavailable: this.catalogUnavailable(),
      hasOpenSession: this.openSession() !== null,
      stockBlocked: this.stockBlocked(),
      writesDisabled: this.writesDisabled(),
    }),
  );
  readonly reserveDisabled = computed(() => {
    if (this.writesDisabled() || this.counterLoading() || this.catalogClosed() || !this.openSession()) return true;
    return this.stockBlocked() && !this.activeSale();
  });

  setHealth(state: { readonly loading: boolean; readonly backendDown: boolean; readonly integrationBlocked: boolean }): void {
    this.healthLoading.set(state.loading);
    this.backendDown.set(state.backendDown);
    this.integrationBlocked.set(state.integrationBlocked);
  }

  blockWrites(): void {
    this.writesDisabled.set(true);
    this.killPrompt.set(true);
  }

  dismissKill(): void {
    this.killPrompt.set(false);
  }

  blockStock(availableQuantity: number | null): void {
    this.stockBlocked.set(true);
    this.promptToken += 1;
    this.ticketPrompt.set({ token: this.promptToken, kind: 'stock', availableQuantity });
  }

  markCatalogStale(): void {
    const current = this.catalog();
    if (current) this.catalog.set({ ...current, stale: true });
    else this.catalogUnavailable.set(true);
    this.promptToken += 1;
    this.ticketPrompt.set({ token: this.promptToken, kind: 'stale', availableQuantity: null });
  }

  clearTicketPrompt(): void {
    this.ticketPrompt.set(null);
  }

  rememberSale(sale: SaleSnapshot): void {
    this.activeSale.set(sale);
    this.salesById.update((current) => {
      const next = { ...current, [sale.saleId]: sale };
      writeJson(SALES_KEY, next);
      return next;
    });
  }

  rememberExpense(expense: ExpenseLog): void {
    this.expenseLog.update((current) => {
      const next = [...current, expense];
      writeJson(EXPENSES_KEY, next);
      return next;
    });
  }

  resetForActor(): void {
    this.activeSale.set(null);
    this.stockBlocked.set(false);
    this.ticketPrompt.set(null);
    this.ticketError.set(null);
    this.ticketMessage.set(null);
    this.cashError.set(null);
    this.cashNotice.set(null);
    this.salesById.set({});
    this.expenseLog.set([]);
    writeJson(SALES_KEY, {});
    writeJson(EXPENSES_KEY, []);
  }
}

function readSales(): Readonly<Record<string, SaleSnapshot>> {
  const raw = readJson<Record<string, SaleSnapshot>>(SALES_KEY, {});
  return Object.fromEntries(Object.entries(raw).map(([id, sale]) => [id, hydrateSale(sale)]));
}

function hydrateSale(sale: SaleSnapshot): SaleSnapshot {
  return {
    ...sale,
    status: SaleStatus.fromWire(sale.status),
    payments: (sale.payments ?? []).map(hydratePayment),
  };
}

function hydratePayment(payment: RecordedPayment): RecordedPayment {
  return {
    ...payment,
    method: PosPaymentMethod.fromWire(payment.method),
    status: PaymentStatus.fromWire(payment.status),
  };
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    // The console still works for this tab if storage is blocked.
  }
}

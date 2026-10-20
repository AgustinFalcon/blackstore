import { Page } from '@playwright/test';
import { test, expect } from './fixtures/dct-runtime';
import { StaffSessionPage } from './pages/staff-session-page';
import { CashSessionPage } from './pages/cash-session-page';
import { AccountingLifecycleState } from '../../src/app/core/domain/accounting-lifecycle';
import { CommandFailure, CommandOutcome, ExpenseOperation } from '../../src/app/core/domain/accounting-command';
import { AccountingMetric, CompletenessCause, DataCompleteness, ReconciliationOutcome } from '../../src/app/core/domain/accounting-report';
import { PaymentCoverage } from '../../src/app/core/domain/ticket-transition';
import { CashMutationOutcome, PaymentMethod, PaymentStatus, StaffRole } from '../../src/app/core/domain/pos-types';
import { DurableSaleState } from '../../src/app/core/domain/durable-sale';

interface BrowserResponse {
  readonly status: number;
  readonly body: { readonly data: Record<string, unknown>; readonly errorCode?: unknown };
}

/** Runs through Chromium with its HttpOnly SID and a server-issued CSRF token. */
async function mutate(page: Page, path: string, body: object): Promise<BrowserResponse> {
  return page.evaluate(async ({ path, body }) => {
    const csrf = await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json();
    const response = await fetch(path, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf.data.csrfToken },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json() };
  }, { path, body });
}

async function logout(page: Page): Promise<void> {
  const response = page.waitForResponse(value => value.request().method() === 'POST' && value.url().endsWith('/api/v1/auth/logout'));
  await page.getByRole('banner').getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  expect((await response).status()).toBe(204);
}

async function selectShift(page: Page, cashId: number): Promise<void> {
  const select = page.getByRole('region', { name: 'Turno' }).getByRole('combobox', { name: 'Caja visible' });
  await expect.poll(() => select.locator('option').count()).toBeGreaterThan(1);
  const options = select.locator('option');
  const labels = await options.allTextContents();
  const index = labels.findIndex(label => label.replace(/\s+/g, ' ').trim().startsWith(`Caja ${cashId} `));
  expect(index).toBeGreaterThan(0);
  await select.selectOption(requireValue(await options.nth(index).getAttribute('value')));
  const response = page.waitForResponse(value => value.request().method() === 'GET' && value.url().includes('/api/v2/reports/shift'));
  await page.getByRole('button', { name: 'Consultar turno', exact: true }).click();
  const report = await response;
  expect(report.status(), await report.text()).toBe(200);
  await expect(page.getByRole('article', { name: 'Reporte de turno' })).toBeVisible();
}

function requireValue(value: string | null): string {
  if (!value) throw new Error('Expected a selectable cash-session value');
  return value;
}

test('browser matrix: partial capture, split CASH/CARD, component reversals and final release', async ({ runtime, page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const staff = await runtime.database.seed(StaffRole.Cashier);
  const terminal = await runtime.database.terminal();
  await new StaffSessionPage(page).login(staff);
  const cash = new CashSessionPage(page);
  await cash.visit(terminal);
  await cash.open('0');

  await page.goto('/catalogo');
  await expect(page.getByRole('heading', { name: 'Catálogo', exact: true })).toBeVisible();
  await page.goto('/ticket');
  await expect.poll(async () => Number(await page.getByLabel('Precio', { exact: true }).inputValue())).toBeGreaterThan(100);
  const catalogPrice = Number(await page.getByLabel('Precio', { exact: true }).inputValue());
  await page.getByLabel('Descuento', { exact: true }).fill('0');
  await page.getByLabel('Importe efectivo', { exact: true }).fill('12');
  await page.getByRole('combobox', { name: 'Segundo medio' }).selectOption({ label: PaymentMethod.Card.label });
  const expectedSecondAmount = catalogPrice - 12;
  expect(expectedSecondAmount).toBeGreaterThan(0);
  await page.getByLabel('Segundo importe', { exact: true }).fill(String(expectedSecondAmount));
  const reserve = page.getByRole('button', { name: 'Reservar y cobrar', exact: true });
  await expect(reserve).toBeEnabled();
  const reservation = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith('/api/v2/sales/reservations'));
  await reserve.click();
  const reservationBody = (await reservation).postDataJSON();
  const operationId = String(reservationBody['operationId']);
  const saleTotal = Number(reservationBody['originalUnitPrice']) - Number(reservationBody['discountAmount']);
  const secondAmount = saleTotal - 12;
  expect(saleTotal).toBe(catalogPrice);
  await expect.poll(async () => DurableSaleState.fromWire((await runtime.database.query(
    'SELECT status FROM sale_state_projection WHERE operation_id=$1', [operationId],
  ))[0]?.status)).toBe(DurableSaleState.Reserved);
  await expect(page.getByText(new RegExp(PaymentCoverage.Paid.label), { exact: false })).toBeVisible();

  await page.goto('/ventas');
  const sale = page.locator('article').filter({ hasText: operationId });
  await expect(sale).toBeVisible();
  await sale.getByRole('button', { name: 'Reabrir', exact: true }).click();
  await expect(page.getByText(new RegExp(`${PaymentMethod.Cash.label} · ${PaymentStatus.Captured.label} · 12\.00`))).toBeVisible();
  await expect(page.getByText(new RegExp(`${PaymentMethod.Card.label} · ${PaymentStatus.Captured.label} · ${secondAmount.toFixed(2).replace('.', '\\.')}`))).toBeVisible();

  await page.getByLabel('Motivo de operación', { exact: true }).fill('reversa componente efectivo');
  await page.getByRole('button', { name: /^Reversar pago \d+$/ }).first().click();
  await page.getByRole('dialog', { name: 'Confirmar reversa' }).getByRole('button', { name: 'Confirmar reversa', exact: true }).click();
  await expect(page.getByText(new RegExp(PaymentCoverage.Partial.label), { exact: false })).toBeVisible();
  await page.getByLabel('Motivo de operación', { exact: true }).fill('reversa componente tarjeta');
  await page.getByRole('button', { name: /^Reversar pago \d+$/ }).click();
  await page.getByRole('dialog', { name: 'Confirmar reversa' }).getByRole('button', { name: 'Confirmar reversa', exact: true }).click();
  await expect(page.getByText(new RegExp(PaymentCoverage.Unpaid.label), { exact: false })).toBeVisible();
  await page.getByLabel('Motivo de operación', { exact: true }).fill('liberar reserva sin cobros netos');
  await page.getByRole('button', { name: 'Liberar reserva', exact: true }).click();
  await page.getByRole('dialog', { name: 'Liberar reserva' }).getByRole('button', { name: 'Liberar reserva', exact: true }).click();
  await expect.poll(async () => DurableSaleState.fromWire((await runtime.database.query(
    'SELECT status FROM sale_state_projection WHERE operation_id=$1', [operationId],
  ))[0].status)).toBe(DurableSaleState.Released);
  await page.getByRole('button', { name: 'Reintentar consulta', exact: true }).click();
  const released = page.locator('article').filter({ hasText: operationId });
  await expect(released).toContainText(DurableSaleState.Released.label);
  await released.getByRole('button', { name: 'Reabrir', exact: true }).click();
  await expect(page.getByRole('heading', { name: DurableSaleState.Released.label, exact: true })).toBeVisible();
});

test('browser matrix: accrual settlement, all reconciliation outcomes, SHIFT/DAY filters and unknown neutrality', async ({ runtime, page }) => {
  const cashier = await runtime.database.seed(StaffRole.Cashier);
  const owner = await runtime.database.seed(StaffRole.Owner);
  const terminal = await runtime.database.terminal();
  await new StaffSessionPage(page).login(cashier);
  const cash = new CashSessionPage(page);
  await cash.visit(terminal);

  const balancedId = await cash.open('0');
  const accrued = await mutate(page, '/api/v2/expenses', {
    commandId: crypto.randomUUID(), cashSessionId: balancedId, operation: ExpenseOperation.Accrue.wire,
    category: 'servicios', amount: 15, reason: 'devengo browser',
  });
  expect(accrued.status).toBe(CashMutationOutcome.Applied.httpStatus);
  expect(CommandOutcome.fromWire(accrued.body.data['outcome'])).toBe(CommandOutcome.Committed);
  expect(accrued.body.data['settlementId']).toBeNull();
  const expenseId = Number(accrued.body.data['expenseId']);
  expect(expenseId).toBeGreaterThan(0);
  const settled = await mutate(page, '/api/v2/expenses', {
    commandId: crypto.randomUUID(), cashSessionId: balancedId, operation: ExpenseOperation.SettleExisting.wire,
    expenseId, reason: 'liquidación total browser', paymentMethod: PaymentMethod.Card.wire,
  });
  expect(settled.status).toBe(CashMutationOutcome.Applied.httpStatus);
  expect(Number(settled.body.data['expenseId'])).toBe(expenseId);
  expect(Number(settled.body.data['settlementId'])).toBeGreaterThan(0);
  await cash.close(balancedId, '0');

  await cash.visit(terminal);
  const shortageId = await cash.open('100');
  await cash.expense('10');
  await cash.close(shortageId, '80');
  await cash.visit(terminal);
  const overageId = await cash.open('100');
  await cash.close(overageId, '110');

  const otherCashier = await runtime.database.seed(StaffRole.Cashier);
  const emptyTerminal = Number((await runtime.database.query(
    "INSERT INTO terminals(terminal_code,active) VALUES('DCT-FILTER-EMPTY',true) RETURNING id",
  ))[0].id);
  await logout(page);
  await new StaffSessionPage(page).login(otherCashier);
  await cash.visit(terminal);
  const excludedId = await cash.open('500');
  await cash.expense('70');
  await cash.close(excludedId, '430');

  await logout(page);
  await new StaffSessionPage(page).login(owner);
  await page.goto('/reportes');
  await selectShift(page, balancedId);
  await expect(page.getByRole('heading', { name: `Arqueo · ${ReconciliationOutcome.Balanced.label}`, exact: true })).toBeVisible();
  await expect(page.getByRole('article', { name: 'Reporte de turno' }).getByText(new RegExp(DataCompleteness.Complete.label), { exact: false }).first()).toBeVisible();
  await selectShift(page, shortageId);
  await expect(page.getByRole('heading', { name: `Arqueo · ${ReconciliationOutcome.Shortage.label}`, exact: true })).toBeVisible();
  await selectShift(page, overageId);
  await expect(page.getByRole('heading', { name: `Arqueo · ${ReconciliationOutcome.Overage.label}`, exact: true })).toBeVisible();

  const zone = 'America/Argentina/Buenos_Aires';
  const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(new Date());
  await page.getByLabel('Fecha local', { exact: true }).fill(localDate);
  await page.getByLabel('Zona IANA autorizada', { exact: true }).fill(zone);
  await page.getByLabel('Terminal', { exact: true }).fill(String(terminal));
  await page.getByLabel('Titular', { exact: true }).fill(String(cashier.id));
  await page.getByRole('button', { name: 'Consultar día', exact: true }).click();
  const day = page.getByRole('article', { name: 'Reporte de día' });
  await expect(day).toBeVisible();
  await expect(day.getByText('Al corte / as-of', { exact: true })).toBeVisible();
  await expect(day.getByText(new RegExp(DataCompleteness.Partial.label), { exact: false }).first()).toBeVisible();
  await expect(day.getByText(CompletenessCause.BeforeActivation.label, { exact: false }).first()).toBeVisible();
  const cashMovements = day.getByRole('region', { name: `Movimientos en ${PaymentMethod.Cash.label}` });
  const paidExpenses = cashMovements.locator('.metric').filter({ hasText: AccountingMetric.ExpensesPaid.label });
  await expect(paidExpenses.locator('dd')).toContainText('10.00');
  await expect(day).not.toContainText('70.00');

  await page.getByLabel('Terminal', { exact: true }).fill(String(emptyTerminal));
  await page.getByRole('button', { name: 'Consultar día', exact: true }).click();
  await expect(day).toBeVisible();
  await expect(day).not.toContainText('10.00');

  await page.route('**/api/v2/reports/shift?*', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.data.reconciliation.outcome = 'FUTURE_OUTCOME';
    await route.fulfill({ response, json: body });
  }, { times: 1 });
  await selectShift(page, balancedId);
  await expect(page.getByRole('heading', { name: `Arqueo · ${ReconciliationOutcome.Unknown.label}`, exact: true })).toBeVisible();
});

test('browser matrix: PAUSED blocks mutations while authenticated GET reports remain available', async ({ runtime, page }) => {
  const cashier = await runtime.database.seed(StaffRole.Cashier);
  const owner = await runtime.database.seed(StaffRole.Owner);
  const terminal = await runtime.database.terminal();
  await new StaffSessionPage(page).login(cashier);
  const cash = new CashSessionPage(page);
  await cash.visit(terminal);
  const cashId = await cash.open('25');
  await cash.close(cashId, '25');
  const legacyActive = await mutate(page, '/api/v1/cash-sessions', {
    terminalId: terminal, cashierId: cashier.id, openingCash: 0, reason: 'legacy válido bloqueado en ACTIVE',
  });
  expect(legacyActive.status).toBe(CashMutationOutcome.Conflict.httpStatus);
  expect(CommandFailure.fromWire(legacyActive.body.errorCode ?? legacyActive.body.data?.failure))
    .toBe(CommandFailure.LegacyDisabled);
  await runtime.database.query('UPDATE accounting_runtime SET state=$1 WHERE singleton', [AccountingLifecycleState.Paused.wire]);
  await page.goto('/caja');
  await expect(page.getByText(AccountingLifecycleState.Paused.label, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Abrir sesión', exact: true })).toBeDisabled();
  const blocked = await mutate(page, '/api/v2/cash-sessions', {
    commandId: crypto.randomUUID(), terminalId: terminal, cashierId: cashier.id, openingCash: 0, reason: 'paused browser',
  });
  expect(blocked.status).toBe(CashMutationOutcome.Conflict.httpStatus);
  expect(CommandFailure.fromWire(blocked.body.data['failure'])).toBe(CommandFailure.Paused);
  const legacyPaused = await mutate(page, '/api/v1/cash-sessions', {
    terminalId: terminal, cashierId: cashier.id, openingCash: 0, reason: 'legacy válido bloqueado en PAUSED',
  });
  expect(legacyPaused.status).toBe(CashMutationOutcome.Conflict.httpStatus);
  expect(CommandFailure.fromWire(legacyPaused.body.errorCode ?? legacyPaused.body.data?.failure))
    .toBe(CommandFailure.LegacyDisabled);

  await logout(page);
  await new StaffSessionPage(page).login(owner);
  await page.goto('/reportes');
  await selectShift(page, cashId);
  await expect(page.getByRole('article', { name: 'Reporte de turno' })).toBeVisible();
});

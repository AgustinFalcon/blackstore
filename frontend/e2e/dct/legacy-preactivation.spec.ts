import { Page } from '@playwright/test';
import { legacyTest as test, expect } from './fixtures/dct-runtime';
import { StaffSessionPage } from './pages/staff-session-page';
import { CashSessionPage } from './pages/cash-session-page';
import { AccountingLifecycleState } from '../../src/app/core/domain/accounting-lifecycle';
import { CashMutationOutcome, PaymentMethod, SaleStatus, StaffRole } from '../../src/app/core/domain/pos-types';
import { CommandFailure } from '../../src/app/core/domain/accounting-command';
import { CompletenessCause, DataCompleteness, ReconciliationOutcome } from '../../src/app/core/domain/accounting-report';

async function mutate(page: Page, path: string, body: object) {
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
  const value = await options.nth(index).getAttribute('value');
  if (!value) throw new Error('Expected cash-session option value');
  await select.selectOption(value);
  const response = page.waitForResponse(candidate => candidate.request().method() === 'GET' && candidate.url().includes('/api/v2/reports/shift'));
  await page.getByRole('button', { name: 'Consultar turno', exact: true }).click();
  const report = await response;
  expect(report.status()).toBe(200);
  await expect(page.getByRole('article', { name: 'Reporte de turno' })).toBeVisible();
}

test('legacy browser contract remains accepted only before accounting activation', async ({ runtime, page }) => {
  const staff = await runtime.database.seed(StaffRole.Cashier);
  const terminal = await runtime.database.terminal();
  await new StaffSessionPage(page).login(staff);

  await page.goto('/caja');
  await expect(page.getByText(AccountingLifecycleState.PreActivation.label, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Abrir sesión', exact: true })).toBeDisabled();
  const blockedV2 = await mutate(page, '/api/v2/cash-sessions', {
    commandId: crypto.randomUUID(), terminalId: terminal, cashierId: staff.id, openingCash: 0, reason: 'blocked before activation',
  });
  expect(blockedV2.status).toBe(CashMutationOutcome.Conflict.httpStatus);
  expect(CommandFailure.fromWire(blockedV2.body.data.failure)).toBe(CommandFailure.NotActivated);

  const open = await mutate(page, '/api/v1/cash-sessions', {
    terminalId: terminal, cashierId: staff.id, openingCash: 100, reason: 'legacy-preactivation-dct',
  });
  expect(open.status).toBe(200);
  const cashId = Number(open.body.data.id);

  const expense = await mutate(page, '/api/v1/expenses', {
    cashSessionId: cashId, category: 'insumos', amount: 7.25,
    reason: 'legacy-preactivation-dct', method: PaymentMethod.Cash.wire,
  });
  expect(expense.status).toBe(200);

  const close = await mutate(page, `/api/v1/cash-sessions/${cashId}/close`, {
    declared: 92.75, reason: 'legacy-preactivation-dct',
  });
  expect(close.status).toBe(200);

  const runtimeState = await runtime.database.query('SELECT state,accounting_activation_at FROM accounting_runtime WHERE singleton');
  expect(AccountingLifecycleState.fromWire(runtimeState[0].state)).toBe(AccountingLifecycleState.PreActivation);
  expect(runtimeState[0].accounting_activation_at).toBeNull();
  expect((await runtime.database.query('SELECT count(*)::int AS count FROM accounting_command_receipts'))[0].count).toBe(0);
  const facts = await runtime.database.facts(cashId);
  expect(facts.cash.closing_cash_declared).toBe('92.75');
  expect(facts.expenses).toHaveLength(1);
});

test('mixed DAY keeps legacy cash and a pre-activation committed sale incomplete beside a complete V2 shift', async ({ runtime, page }) => {
  const cashier = await runtime.database.seed(StaffRole.Cashier);
  const owner = await runtime.database.seed(StaffRole.Owner);
  const terminal = await runtime.database.terminal();
  await new StaffSessionPage(page).login(cashier);

  const opened = await mutate(page, '/api/v1/cash-sessions', {
    terminalId: terminal, cashierId: cashier.id, openingCash: 40, reason: 'legacy mixed-day fixture',
  });
  expect(opened.status).toBe(200);
  const legacyCashId = Number(opened.body.data.id);
  const legacyClose = await mutate(page, `/api/v1/cash-sessions/${legacyCashId}/close`, {
    declared: 40, reason: 'legacy mixed-day fixture',
  });
  expect(legacyClose.status).toBe(200);

  const clientInstanceId = crypto.randomUUID();
  const operationId = crypto.randomUUID();
  const saleIntent = await runtime.database.query(
    "INSERT INTO sale_intents(client_instance_id,device_id,sale_id,operation_id,cash_session_id,created_by,contract_version,openapi_digest) VALUES($1,'legacy-browser','legacy-sale',$2,$3,$4,'legacy-v1',repeat('a',64)) RETURNING id",
    [clientInstanceId, operationId, legacyCashId, cashier.id],
  );
  await runtime.database.query(
    "INSERT INTO sale_state_projection(sale_intent_id,client_instance_id,device_id,sale_id,operation_id,aggregate_operation_key) VALUES($1,$2,'legacy-browser','legacy-sale',$3,$3)",
    [Number(saleIntent[0].id), clientInstanceId, operationId],
  );
  await runtime.database.query(
    "UPDATE sale_state_projection SET status='RESERVED',storecore_reservation_ref=$2,reservation_receipt='legacy-receipt',contract_version='legacy-v1',openapi_digest=repeat('a',64),accepted_price_versions='[\"legacy-price\"]'::jsonb,reservation_expires_at=clock_timestamp()+interval '1 hour',gross_sales=30,version=version+1,updated_at=clock_timestamp() WHERE operation_id=$1",
    [operationId, `legacy-reservation-${operationId}`],
  );
  for (const status of [SaleStatus.PaymentCaptured, SaleStatus.CommitPending, SaleStatus.Committed]) {
    await runtime.database.query(
      'UPDATE sale_state_projection SET status=$2,version=version+1,updated_at=clock_timestamp() WHERE operation_id=$1',
      [operationId, status.wire],
    );
  }

  await runtime.database.query(
    "UPDATE accounting_runtime SET state='ACTIVE',accounting_activation_at=clock_timestamp() WHERE singleton AND state='PRE_ACTIVATION'",
  );
  await page.goto('/caja');
  await expect(page.getByText(AccountingLifecycleState.Active.label, { exact: true })).toBeVisible();
  const cash = new CashSessionPage(page);
  await cash.visit(terminal);
  const completeCashId = await cash.open('0');
  await cash.close(completeCashId, '0');

  await logout(page);
  await new StaffSessionPage(page).login(owner);
  await page.goto('/reportes');
  await selectShift(page, legacyCashId);
  await expect(page.getByRole('heading', { name: `Arqueo · ${ReconciliationOutcome.Unavailable.label}`, exact: true })).toBeVisible();
  await expect(page.getByText(DataCompleteness.LegacyIncomplete.label, { exact: false }).first()).toBeVisible();
  await selectShift(page, completeCashId);
  await expect(page.getByRole('heading', { name: `Arqueo · ${ReconciliationOutcome.Balanced.label}`, exact: true })).toBeVisible();

  const zone = 'America/Argentina/Buenos_Aires';
  const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: zone }).format(new Date());
  await page.getByLabel('Fecha local', { exact: true }).fill(localDate);
  await page.getByLabel('Zona IANA autorizada', { exact: true }).fill(zone);
  await page.getByRole('button', { name: 'Consultar día', exact: true }).click();
  const day = page.getByRole('article', { name: 'Reporte de día' });
  await expect(day).toBeVisible();
  await expect(day.getByText(CompletenessCause.LegacyActivity.label, { exact: false }).first()).toBeVisible();
  await expect(day.getByText(new RegExp(DataCompleteness.Partial.label), { exact: false }).first()).toBeVisible();
});

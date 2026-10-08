import { CommandFailure, ExpenseOperation } from '../../src/app/core/domain/accounting-command';
import { BrowserContext, Page } from '@playwright/test';
import { test, expect } from './fixtures/dct-runtime';
import { StaffSessionPage } from './pages/staff-session-page';
import { CashSessionPage } from './pages/cash-session-page';
import { CashMutationOutcome, PaymentMethod, StaffRole } from '../../src/app/core/domain/pos-types';
import { PosWireMapper } from '../../src/app/core/infrastructure/pos-wire-mapper';
import { DctAuditEvent } from './fixtures/dct-audit-event';
import { AccountingLifecycleState } from '../../src/app/core/domain/accounting-lifecycle';

/** Requests run in Chromium with its real HttpOnly cookie and the server-issued CSRF. */
async function mutate(page: Page, path: string, body: object, invalidCsrf = false) {
  return page.evaluate(async ({ path, body, invalidCsrf }) => {
    const csrf = await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json();
    const response = await fetch(path, {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': invalidCsrf ? 'invalid' : csrf.data.csrfToken },
      body: JSON.stringify({ ...body, commandId: crypto.randomUUID() }),
    });
    return { status: response.status, cacheControl: response.headers.get('cache-control'), body: await response.json() };
  }, { path, body, invalidCsrf });
}

/** A typed denial may contain data, but never identifiers, facts or snapshots. */
function opaqueDenial(response: Awaited<ReturnType<typeof mutate>>) {
  const data = response.body.data;
  expect(data == null || typeof data === 'object' && !Array.isArray(data)).toBe(true);
  for (const key of ['commandId', 'cashSessionId', 'saleId', 'paymentId', 'expenseId', 'settlementId',
    'actorId', 'terminalId', 'clientInstanceId', 'deviceId', 'committedAt', 'payloadHash', 'closeSnapshot']) {
    expect(data?.[key] ?? null, `Opaque denial must not reveal ${key}`).toBeNull();
  }
  expect(data?.ledgerEventIds ?? []).toEqual([]);
  const body = { ...response.body };
  delete body.traceId; // the sole per-request difference allowed in opaque responses
  return { status: response.status, cacheControl: response.cacheControl, body };
}

test('denials: real authority/CSRF/unknown method and stale UI conflict never create a partial mutation', async ({ runtime, browser, page }) => {
  const own = await runtime.database.seed(StaffRole.Cashier);
  const other = await runtime.database.seed(StaffRole.Cashier);
  const owner = await runtime.database.seed(StaffRole.Owner);
  const auditor = await runtime.database.seed(StaffRole.Auditor);
  const terminal = runtime.terminalId;
  const contexts: BrowserContext[] = [];
  async function authenticated(staff: typeof own) {
    const context = await browser.newContext({ baseURL: 'http://localhost:4201' });
    contexts.push(context);
    const actorPage = await context.newPage();
    await new StaffSessionPage(actorPage).login(staff);
    return actorPage;
  }
  async function denied(actorPage: Page, path: string, body: object, outcome: CashMutationOutcome, invalidCsrf = false) {
    const response = await mutate(actorPage, path, body, invalidCsrf);
    expect(response.status).toBe(outcome.httpStatus);
    const cacheDirectives = new Set((response.cacheControl ?? '').split(',').map(value => value.trim()).filter(Boolean));
    expect(cacheDirectives).toEqual(new Set(['no-store']));
    if (response.body.data) {
      const expected = outcome === CashMutationOutcome.NotVisible ? CommandFailure.NotVisible : outcome === CashMutationOutcome.Validation ? CommandFailure.Validation :
        outcome === CashMutationOutcome.Conflict ? CommandFailure.Closed : CommandFailure.Forbidden;
      expect(PosWireMapper.commandReceipt(response.body, '').failure).toBe(expected);
    } else expect(PosWireMapper.cashMutationOutcome(response.body, response.status)).toBe(outcome);
    // v2 application failures carry a closed typed receipt, while SID failures have no data.
    if (response.body.data) expect(PosWireMapper.commandReceipt(response.body, '').failure).not.toBe(CommandFailure.Unknown);
    return response;
  }
  try {
    await page.goto('/sesion');
    await denied(page, '/api/v2/cash-sessions', { terminalId: terminal, cashierId: own.id, openingCash: 0 }, CashMutationOutcome.Unauthenticated);
    await new StaffSessionPage(page).login(own);
    const cashPage = new CashSessionPage(page);
    await cashPage.visit(terminal);
    const cashId = await cashPage.open('100');
    const baseline = await runtime.database.facts(cashId);
    const expense = { cashSessionId: cashId, category: 'insumos', reason: 'bolsas', amount: 7.25, operation: ExpenseOperation.AccrueAndSettle.wire, paymentMethod: PaymentMethod.Cash.wire };
    await denied(page, '/api/v2/expenses', expense, CashMutationOutcome.Forbidden, true);
    const otherPage = await authenticated(other);
    const foreign = await denied(otherPage, '/api/v2/expenses', expense, CashMutationOutcome.NotVisible);
    const missing = await denied(otherPage, '/api/v2/expenses', { ...expense, cashSessionId: Number.MAX_SAFE_INTEGER }, CashMutationOutcome.NotVisible);
    expect(opaqueDenial(foreign)).toEqual(opaqueDenial(missing));
    const ownerPage = await authenticated(owner);
    await denied(ownerPage, `/api/v2/cash-sessions/${cashId}/close`, { cashSessionId: cashId, declaredCash: 0, reason: '' }, CashMutationOutcome.Validation);
    await denied(page, '/api/v2/expenses', { ...expense, paymentMethod: PaymentMethod.Unknown.wire }, CashMutationOutcome.Validation);
    const auditorPage = await authenticated(auditor);
    await denied(auditorPage, '/api/v2/expenses', expense, CashMutationOutcome.Forbidden);
    expect(await runtime.database.facts(cashId)).toEqual(baseline);
    // Second independent session closes while the original UI still sees an open session.
    const closingPage = await authenticated(own);
    const closingCash = new CashSessionPage(closingPage);
    await closingCash.visit(terminal);
    await closingCash.close(cashId, '100');
    const closed = await runtime.database.facts(cashId);
    const foreignClosed = await denied(otherPage, '/api/v2/expenses', expense, CashMutationOutcome.NotVisible);
    expect(opaqueDenial(foreignClosed)).toEqual(opaqueDenial(foreign));
    await denied(page, '/api/v2/expenses', expense, CashMutationOutcome.Conflict);
    let posts = 0;
    let cashReads = 0;
    page.on('request', request => {
      if (request.method() === 'POST' && request.url().endsWith('/api/v2/expenses')) posts++;
      if (request.method() === 'GET' && request.url().endsWith('/api/v1/cash-sessions')) cashReads++;
    });
    const refreshed = page.waitForResponse(r => r.url().endsWith('/api/v1/cash-sessions') && r.request().method() === 'GET');
    // Fresh reference GET prevents sending a stale mutation against a closed box.
    await page.getByLabel('Gasto', { exact: true }).fill('7.25');
    await page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    expect((await refreshed).status()).toBe(200);
    await expect(page.getByText(CommandFailure.Unknown.label, { exact: true }).first()).toBeVisible();
    expect(cashReads).toBeGreaterThanOrEqual(1);
    expect(posts).toBe(0);
    const reloaded = page.waitForResponse(r => r.url().endsWith('/api/v1/cash-sessions') && r.request().method() === 'GET');
    await page.reload();
    expect((await reloaded).status()).toBe(200);
    await cashPage.expectClosedWorkstation(terminal, own.id);
    expect(posts).toBe(0);
    expect(await runtime.database.facts(cashId)).toEqual(closed);
    // ACTIVE permanently fences the legacy writer, and PAUSED also fences v2.
    const legacy = await mutate(page, '/api/v1/expenses', { cashSessionId: cashId, category: 'insumos', reason: 'bolsas', amount: 7.25, method: PaymentMethod.Cash.wire });
    expect(legacy.status).toBe(409);
    expect(PosWireMapper.commandReceipt(legacy.body, '').failure).toBe(CommandFailure.LegacyDisabled);
    await runtime.database.query('UPDATE accounting_runtime SET state=$1 WHERE singleton', [AccountingLifecycleState.Paused.wire]);
    const paused = await mutate(page, '/api/v2/expenses', expense);
    expect(paused.status).toBe(409);
    expect(PosWireMapper.commandReceipt(paused.body, '').failure).toBe(CommandFailure.Paused);
    expect(await runtime.database.facts(cashId)).toEqual(closed);
    // Three application-level authority denials: other open/missing/closed.
    const counts = await runtime.database.query('SELECT actor_id,count(*)::int AS count FROM audit_events WHERE event_type=$1 GROUP BY actor_id', [DctAuditEvent.AuthorizationDenied.wire]);
    expect(counts.find(row => Number(row.actor_id) === other.id)?.count).toBe(3);
    // Blank reason fails request validation before application authority.
    expect(closed.expenses).toHaveLength(0);
    expect(closed.audit).toHaveLength(2);
    expect(closed.receipts).toHaveLength(2);
    expect(closed.ledger).toHaveLength(1);
    expect(closed.settlements).toHaveLength(0);
    expect((await runtime.database.query('SELECT count(*)::int AS count FROM cash_session_projection'))[0].count).toBe(1);
    expect((await runtime.database.query('SELECT count(*)::int AS count FROM expenses'))[0].count).toBe(0);
  } finally { for (const context of contexts) await context.close(); }
});

import { BrowserContext, Page } from '@playwright/test';
import { test, expect } from './fixtures/dct-runtime';
import { StaffSessionPage } from './pages/staff-session-page';
import { CashSessionPage } from './pages/cash-session-page';
import { CashMutationOutcome, PaymentMethod, StaffRole } from '../../src/app/core/domain/pos-types';
import { PosWireMapper } from '../../src/app/core/infrastructure/pos-wire-mapper';
import { DctAuditEvent } from './fixtures/dct-audit-event';

/** Requests run in Chromium with its real HttpOnly cookie and the server-issued CSRF. */
async function mutate(page: Page, path: string, body: object, invalidCsrf = false) {
  return page.evaluate(async ({ path, body, invalidCsrf }) => {
    const csrf = await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json();
    const response = await fetch(path, {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': invalidCsrf ? 'invalid' : csrf.data.csrfToken },
      body: JSON.stringify(body),
    });
    return { status: response.status, cacheControl: response.headers.get('cache-control'), body: await response.json() };
  }, { path, body, invalidCsrf });
}

test('denials: real authority/CSRF/unknown method and stale UI conflict never create a partial mutation', async ({ runtime, browser, page }) => {
  const own = await runtime.database.seed(StaffRole.Cashier);
  const other = await runtime.database.seed(StaffRole.Cashier);
  const owner = await runtime.database.seed(StaffRole.Owner);
  const auditor = await runtime.database.seed(StaffRole.Auditor);
  const terminal = await runtime.database.terminal();
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
    expect(response.cacheControl).toBe('no-store');
    expect(PosWireMapper.cashMutationOutcome(response.body, response.status)).toBe(outcome);
    expect(response.body.data).toBeNull();
    return response;
  }
  try {
    await page.goto('/sesion');
    await denied(page, '/api/v1/cash-sessions', { terminalId: terminal, cashierId: own.id, openingCash: 0 }, CashMutationOutcome.Unauthenticated);
    await new StaffSessionPage(page).login(own);
    const cashPage = new CashSessionPage(page);
    await cashPage.visit(terminal);
    const cashId = await cashPage.open('100');
    const baseline = await runtime.database.facts(cashId);
    const expense = { cashSessionId: cashId, category: 'insumos', reason: 'bolsas', amount: 7.25, method: PaymentMethod.Cash.wire };
    await denied(page, '/api/v1/expenses', expense, CashMutationOutcome.Forbidden, true);
    const otherPage = await authenticated(other);
    const foreign = await denied(otherPage, '/api/v1/expenses', expense, CashMutationOutcome.NotVisible);
    const missing = await denied(otherPage, '/api/v1/expenses', { ...expense, cashSessionId: Number.MAX_SAFE_INTEGER }, CashMutationOutcome.NotVisible);
    expect(foreign.body.message).toBe(missing.body.message);
    const ownerPage = await authenticated(owner);
    await denied(ownerPage, `/api/v1/cash-sessions/${cashId}/close`, { declared: 0 }, CashMutationOutcome.Validation);
    await denied(page, '/api/v1/expenses', { ...expense, method: PaymentMethod.Unknown.wire }, CashMutationOutcome.Validation);
    const auditorPage = await authenticated(auditor);
    await denied(auditorPage, '/api/v1/expenses', expense, CashMutationOutcome.Forbidden);
    expect(await runtime.database.facts(cashId)).toEqual(baseline);
    // Second independent session closes while the original UI still sees an open session.
    const closingPage = await authenticated(own);
    const closingCash = new CashSessionPage(closingPage);
    await closingCash.visit(terminal);
    await closingCash.close(cashId, '100');
    const closed = await runtime.database.facts(cashId);
    const foreignClosed = await denied(otherPage, '/api/v1/expenses', expense, CashMutationOutcome.NotVisible);
    expect(foreignClosed.body.message).toBe(foreign.body.message);
    let posts = 0;
    let cashReads = 0;
    page.on('request', request => {
      if (request.method() === 'POST' && request.url().endsWith('/api/v1/expenses')) posts++;
      if (request.method() === 'GET' && request.url().endsWith('/api/v1/cash-sessions')) cashReads++;
    });
    const refreshed = page.waitForResponse(r => r.url().endsWith('/api/v1/cash-sessions') && r.request().method() === 'GET');
    await cashPage.expense('7.25', CashMutationOutcome.Conflict);
    expect((await refreshed).status()).toBe(200);
    await cashPage.expectClosedWorkstation(terminal, own.id);
    expect(cashReads).toBeGreaterThanOrEqual(1);
    expect(posts).toBe(1);
    const reloaded = page.waitForResponse(r => r.url().endsWith('/api/v1/cash-sessions') && r.request().method() === 'GET');
    await page.reload();
    expect((await reloaded).status()).toBe(200);
    await cashPage.expectClosedWorkstation(terminal, own.id);
    expect(posts).toBe(1);
    expect(await runtime.database.facts(cashId)).toEqual(closed);
    // Four application-level authority denials: other open/missing/closed and owner override.
    const counts = await runtime.database.query('SELECT actor_id,count(*)::int AS count FROM audit_events WHERE event_type=$1 GROUP BY actor_id', [DctAuditEvent.AuthorizationDenied.wire]);
    expect(counts.find(row => Number(row.actor_id) === other.id)?.count).toBe(3);
    expect(counts.find(row => Number(row.actor_id) === owner.id)?.count).toBe(1);
    expect(closed.expenses).toHaveLength(0);
    expect(closed.audit).toHaveLength(2);
    expect((await runtime.database.query('SELECT count(*)::int AS count FROM cash_session_projection'))[0].count).toBe(1);
    expect((await runtime.database.query('SELECT count(*)::int AS count FROM expenses'))[0].count).toBe(0);
  } finally { for (const context of contexts) await context.close(); }
});

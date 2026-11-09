import { BrowserContext, Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { test, expect, DctRuntime } from './fixtures/dct-runtime';
import { DctResponseLoss } from './fixtures/dct-response-loss';
import { journal } from './fixtures/dct-journal';
import { StaffSessionPage } from './pages/staff-session-page';
import { CashSessionPage } from './pages/cash-session-page';
import { acceptLocalCatalog } from './pages/catalog-acceptance';
import { StaffRole } from '../../src/app/core/domain/pos-types';
import { JournalPhase } from '../../src/app/core/domain/command-journal';
import { SaleAdmissionRoute, SaleCommandFailure } from '../../src/app/core/domain/sale-command';
import { DurableSaleState } from '../../src/app/core/domain/durable-sale';

const reservePath = SaleAdmissionRoute.ReserveV3.wire;

async function draft(page: Page) {
  await page.goto('/ticket');
  await acceptLocalCatalog(page);
  await page.getByRole('button', { name: 'Agregar al carrito', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Reservar productos', exact: true })).toBeEnabled();
}

async function logout(page: Page) {
  const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/v1/auth/logout'));
  await page.getByRole('banner').getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
  expect((await response).status()).toBe(204);
}

async function commercialFacts(runtime: DctRuntime) {
  // Excludes security audit/session observations, which denials are allowed to append.
  return (await runtime.database.query(`SELECT
    (SELECT count(*)::int FROM sale_intents) AS intents,
    (SELECT count(*)::int FROM sale_command_admission_receipts) AS receipts,
    (SELECT count(*)::int FROM storecore_outbox_commands) AS outbox,
    (SELECT count(*)::int FROM sale_state_projection) AS tickets,
    (SELECT count(*)::int FROM sale_lines) AS lines,
    (SELECT count(*)::int FROM payments) AS payments`))[0];
}

/** Browser fetch uses the actual cookie and server-issued CSRF; no authority headers. */
async function reserve(page: Page, body: Record<string, unknown>, omitCsrf = false, invalidCsrf = false) {
  return page.evaluate(async ({ body, path, omitCsrf, invalidCsrf }) => {
    const bootstrap = await (await fetch('/api/v1/auth/csrf')).json();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (!omitCsrf) headers['X-CSRF-Token'] = invalidCsrf ? 'invalid' : bootstrap.data.csrfToken;
    const response = await fetch(path, { method: 'POST', credentials: 'same-origin', headers, body: JSON.stringify(body) });
    return { status: response.status, cache: response.headers.get('cache-control'), body: await response.json() };
  }, { body, path: reservePath, omitCsrf, invalidCsrf });
}

test('POSC A07: real SID/CSRF, ownership and role denials leave zero V3 commercial facts', async ({ runtime, page, browser }) => {
  const cashier = await runtime.database.seed(StaffRole.Cashier);
  const foreign = await runtime.database.seed(StaffRole.Cashier);
  const auditor = await runtime.database.seed(StaffRole.Auditor);
  const supervisor = await runtime.database.seed(StaffRole.Supervisor);
  await new StaffSessionPage(page).login(cashier);
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId);
  await cash.open('0');
  await draft(page);
  // Capture a valid UI-produced body, but abort BEFORE forwarding: this test
  // must start with no admitted sale, and never fabricates an accepted response.
  let captured!: (body: Record<string, unknown>) => void;
  const bodyReady = new Promise<Record<string, unknown>>(resolve => { captured = resolve; });
  await page.route(`**${reservePath}`, async route => {
    captured(route.request().postDataJSON());
    await route.abort('connectionreset');
  }, { times: 1 });
  await page.getByRole('button', { name: 'Reservar productos', exact: true }).click();
  const template = await bodyReady;
  const fresh = () => ({ ...template, commandId: randomUUID(), operationId: randomUUID(), saleId: randomUUID() });
  const zero = { intents: 0, receipts: 0, outbox: 0, tickets: 0, lines: 0, payments: 0 };
  expect(await commercialFacts(runtime)).toEqual(zero);
  const contexts: BrowserContext[] = [];
  async function actor(staff?: typeof cashier) {
    const context = await browser.newContext({ baseURL: 'http://localhost:4201' });
    contexts.push(context);
    const target = await context.newPage();
    if (staff) await new StaffSessionPage(target).login(staff);
    else await target.goto('/sesion');
    return target;
  }
  async function denied(target: Page, body: Record<string, unknown>, status: number, failure?: SaleCommandFailure, omitCsrf = false, invalidCsrf = false) {
    const response = await reserve(target, body, omitCsrf, invalidCsrf);
    expect(response.status).toBe(status);
    expect(response.cache).toContain('no-store');
    expect(response.body.data?.receipt ?? null).toBeNull();
    if (failure) expect(SaleCommandFailure.fromWire(response.body.data?.failure)).toBe(failure);
    expect(await commercialFacts(runtime)).toEqual(zero);
    const { traceId: _, ...opaque } = response.body;
    return opaque;
  }
  try {
    await denied(await actor(), fresh(), 401);
    await denied(page, fresh(), 403, undefined, true);
    await denied(page, fresh(), 403, undefined, false, true);
    const foreignPage = await actor(foreign);
    const foreignBody = fresh();
    const hidden = await denied(foreignPage, foreignBody, 404, SaleCommandFailure.NotVisible);
    const missing = await denied(foreignPage, { ...foreignBody, cashSessionId: Number.MAX_SAFE_INTEGER }, 404, SaleCommandFailure.NotVisible);
    expect(hidden).toEqual(missing);
    await denied(await actor(auditor), fresh(), 403);
    await denied(await actor(supervisor), { ...fresh(), reason: null }, 400, SaleCommandFailure.Validation);
    const line = (template['lines'] as Record<string, unknown>[])[0];
    for (const invalidLines of [
      [line, line],
      [{ ...line, quantity: 0 }],
      [{ ...line, quantity: 0.5 }],
      [{ ...line, quantity: null }],
      [{ ...line, quantity: 2147483648 }],
      [{ ...line, unexpected: true }],
    ]) await denied(page, { ...fresh(), lines: invalidLines }, 400);
    // Revocation is done by the real logout endpoint in another tab sharing SID.
    const sibling = await page.context().newPage();
    await sibling.goto('/ticket');
    await logout(sibling);
    await denied(page, fresh(), 401);
    await sibling.close();
  } finally { for (const context of contexts) await context.close(); }
});

function reservePosts(page: Page) {
  const ids: string[] = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith(reservePath)) ids.push(request.postDataJSON().commandId);
  });
  return ids;
}

async function singleReservation(runtime: DctRuntime, operationId: string, commandId: string) {
  await expect.poll(async () => DurableSaleState.fromWire((await runtime.database.query(
    'SELECT status FROM sale_state_projection WHERE operation_id=$1', [operationId]))[0]?.status)).toBe(DurableSaleState.Reserved);
  const facts = await commercialFacts(runtime);
  expect(facts).toEqual({ intents: 1, receipts: 1, outbox: 1, tickets: 1, lines: 1, payments: 0 });
  expect(await runtime.database.query('SELECT command_id FROM sale_command_admission_receipts WHERE command_id=$1', [commandId])).toHaveLength(1);
  await expect.poll(async () => (await runtime.database.query('SELECT count(*)::int AS count FROM storecore_inbox_applications'))[0].count).toBe(1);
  expect((await runtime.database.query('SELECT count(*)::int AS count FROM outbox_delivery_attempts'))[0].count).toBe(1);
  return facts;
}

test('POSC A09: competing hydrated tabs cannot create a second V3 intention; recovery is GET-only', async ({ runtime, page, context }) => {
  const staff = await runtime.database.seed(StaffRole.Cashier);
  await new StaffSessionPage(page).login(staff);
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId); await cash.open('0');
  await draft(page);
  const second = await context.newPage();
  const firstPosts = reservePosts(page), secondPosts = reservePosts(second);
  const loss = new DctResponseLoss(page, `**${reservePath}`);
  try {
    await draft(second);
    await loss.install();
    await page.getByRole('button', { name: 'Reservar productos', exact: true }).click();
    const body = await loss.admitted;
    const commandId = String(body['commandId']), operationId = String(body['operationId']);
    const original = (await journal(page)).find(entry => entry.command.commandId === commandId)!;
    expect(original.phase).toBe(JournalPhase.AwaitingReceipt);
    await second.getByRole('button', { name: 'Reservar productos', exact: true }).click();
    await expect(second.getByText('Resultado incierto o storage no confirmado. Consultá; no reenvíes.', { exact: true })).toBeVisible();
    expect(secondPosts).toEqual([]);
    const before = await singleReservation(runtime, operationId, commandId);
    const read = second.waitForResponse(r => r.request().method() === 'GET' && r.url().endsWith(`/api/v3/sales/commands/${commandId}`));
    await second.reload();
    expect((await read).status()).toBe(200);
    await expect.poll(async () => (await journal(second)).find(entry => entry.command.commandId === commandId)?.phase).toBe(JournalPhase.Resolved);
    await loss.lose();
    await page.reload();
    expect((await journal(page)).find(entry => entry.command.commandId === commandId)).toEqual({ ...original, phase: JournalPhase.Resolved });
    expect(firstPosts).toEqual([commandId]); expect(secondPosts).toEqual([]);
    expect(await singleReservation(runtime, operationId, commandId)).toEqual(before);
  } finally { await loss.dispose(); await second.close(); }
});

test('POSC A09: logout fences a late V3 completion; another actor cannot recover, same actor with new SID uses GET', async ({ runtime, page }) => {
  const first = await runtime.database.seed(StaffRole.Cashier);
  const second = await runtime.database.seed(StaffRole.Cashier);
  const session = new StaffSessionPage(page);
  await session.login(first);
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId); await cash.open('0');
  await draft(page);
  const posts = reservePosts(page);
  const loss = new DctResponseLoss(page, `**${reservePath}`);
  await loss.install();
  try {
    await page.getByRole('button', { name: 'Reservar productos', exact: true }).click();
    const body = await loss.admitted;
    const commandId = String(body['commandId']), operationId = String(body['operationId']);
    const original = (await journal(page)).find(entry => entry.command.commandId === commandId)!;
    const before = await singleReservation(runtime, operationId, commandId);
    await logout(page);
    await session.loginCurrentDocument(second);
    // Keep the document alive across logout/login so the original async task
    // completes against a different session generation, rather than navigation.
    await loss.lose();
    await page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link', { name: 'Ticket', exact: true }).click();
    await expect(page.getByText('Contexto o evidencia pendiente no disponible para esta sesión. Requiere conciliación.', { exact: true })).toBeVisible();
    expect((await journal(page)).find(entry => entry.command.commandId === commandId)).toEqual(original);
    const hidden = await page.evaluate(async id => {
      const r = await fetch(`/api/v3/sales/commands/${id}`); return { status: r.status, body: await r.json() };
    }, commandId);
    expect(hidden.status).toBe(404); expect(hidden.body.data?.receipt ?? null).toBeNull();
    await logout(page);
    const receipt = page.waitForResponse(r => r.request().method() === 'GET' && r.url().endsWith(`/api/v3/sales/commands/${commandId}`));
    await session.loginCurrentDocument(first);
    expect((await receipt).status()).toBe(200);
    await expect.poll(async () => (await journal(page)).find(entry => entry.command.commandId === commandId)?.phase).toBe(JournalPhase.Resolved);
    const sessions = await runtime.database.query('SELECT revoked_at FROM staff_sessions WHERE user_id=$1 ORDER BY created_at', [first.id]);
    expect(sessions).toHaveLength(2); expect(sessions[0].revoked_at).not.toBeNull(); expect(sessions[1].revoked_at).toBeNull();
    expect(posts).toEqual([commandId]);
    expect(await singleReservation(runtime, operationId, commandId)).toEqual(before);
  } finally { await loss.dispose(); }
});

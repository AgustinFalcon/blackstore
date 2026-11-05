import { Page, Route } from '@playwright/test';
import { test, expect, DctRuntime } from './fixtures/dct-runtime';
import { DctResponseLoss } from './fixtures/dct-response-loss';
import { journal } from './fixtures/dct-journal';
import { CashSessionPage } from './pages/cash-session-page';
import { StaffSessionPage } from './pages/staff-session-page';
import { AccountingCommandKind, CommandFailure, CommandOutcome } from '../../src/app/core/domain/accounting-command';
import { JournalFamily, JournalPhase } from '../../src/app/core/domain/command-journal';
import { CashMutationOutcome, CashSessionStatus, StaffRole } from '../../src/app/core/domain/pos-types';
import { DurableCommandKind, DurableSaleState } from '../../src/app/core/domain/durable-sale';
import { SaleCommandKind } from '../../src/app/core/domain/sale-command';
import { PaymentCoverage } from '../../src/app/core/domain/ticket-transition';
import { PosWireMapper } from '../../src/app/core/infrastructure/pos-wire-mapper';

function expensePosts(page: Page): string[] {
  const commands: string[] = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v2/expenses')) commands.push(request.postDataJSON().commandId);
  });
  return commands;
}

async function expenseIntent(page: Page, commandId: string) {
  const entry = (await journal(page)).find(value => value.command.commandId === commandId);
  expect(entry).toBeDefined();
  expect(entry!.family).toBe(JournalFamily.Accounting);
  expect(entry!.command.kind).toBe(AccountingCommandKind.Expense);
  return entry!;
}

async function assertSingleExpense(runtime: DctRuntime, cashId: number, commandId: string) {
  const facts = await runtime.database.facts(cashId);
  expect(facts.expenses).toHaveLength(1);
  expect(facts.settlements).toHaveLength(1);
  expect(facts.receipts).toHaveLength(2); // opening and the single expense
  expect(facts.ledger).toHaveLength(3); // opening, accrual and payment
  expect(facts.audit).toHaveLength(2);
  expect(facts.receipts.filter(row => row.command_id === commandId)).toHaveLength(1);
  expect(facts.receipts.every(row => row.outcome === CommandOutcome.Committed)).toBe(true);
  return facts;
}

test('response-loss: reload recovers the original committed expense by GET without another POST', async ({ runtime, page }) => {
  const staff = await runtime.database.seed(StaffRole.Cashier);
  await new StaffSessionPage(page).login(staff);
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId);
  const cashId = await cash.open('100');
  const posts = expensePosts(page);
  const loss = new DctResponseLoss(page, '**/api/v2/expenses');
  await loss.install();
  try {
    await page.getByLabel('Gasto', { exact: true }).fill('7.25');
    await page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    const body = await loss.admitted;
    const commandId = String(body['commandId']);
    const intent = await expenseIntent(page, commandId);
    expect(intent.phase).toBe(JournalPhase.AwaitingReceipt);
    expect(intent.command.body).toEqual(body);
    expect(intent.actorId).toBe(staff.id);
    const before = await assertSingleExpense(runtime, cashId, commandId);
    await loss.lose();
    await expect(page.getByRole('button', { name: 'Consultar recibo', exact: true })).toBeEnabled();
    const receipt = page.waitForResponse(response => response.request().method() === 'GET' && response.url().endsWith(`/api/v2/accounting/commands/${commandId}`));
    await page.reload();
    expect((await receipt).status()).toBe(200);
    await expect.poll(async () => (await expenseIntent(page, commandId)).phase).toBe(JournalPhase.Resolved);
    await expect(page.getByRole('button', { name: 'Registrar gasto', exact: true })).toBeEnabled();
    const resolved = await expenseIntent(page, commandId);
    expect(resolved).toEqual({ ...intent, phase: JournalPhase.Resolved });
    expect(posts).toEqual([commandId]);
    expect(await runtime.database.facts(cashId)).toEqual(before);
  } finally { await loss.dispose(); }
});

test('cross-tab same SID: a stale hydrated tab cannot claim another intent and GET recovery is monotonic', async ({ runtime, page, context }) => {
  const staff = await runtime.database.seed(StaffRole.Cashier);
  await new StaffSessionPage(page).login(staff);
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId);
  const cashId = await cash.open('100');
  // Same context means the real HttpOnly SID and IndexedDB origin are shared;
  // no second login, cookie extraction or storage injection.
  const second = await context.newPage();
  const loss = new DctResponseLoss(page, '**/api/v2/expenses');
  const firstPosts = expensePosts(page);
  const secondPosts = expensePosts(second);
  try {
    await new CashSessionPage(second).visit(runtime.terminalId);
    await expect(second.getByRole('button', { name: 'Registrar gasto', exact: true })).toBeEnabled();
    await second.getByLabel('Gasto', { exact: true }).fill('9');
    await loss.install();
    await page.getByLabel('Gasto', { exact: true }).fill('7.25');
    await page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    const commandId = String((await loss.admitted)['commandId']);
    const original = await expenseIntent(page, commandId);
    expect(original.phase).toBe(JournalPhase.AwaitingReceipt);
    // B already hydrated before A claimed. Its prepare transaction must reject
    // the competing intention even though its in-memory canWrite was true.
    await expect(second.getByRole('button', { name: 'Registrar gasto', exact: true })).toBeEnabled();
    await second.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    await expect(second.getByText('Resultado incierto o storage no confirmado. Consultá; no reenvíes.', { exact: true })).toBeVisible();
    expect(secondPosts).toEqual([]);
    expect(await journal(second)).toEqual(await journal(page));
    const before = await assertSingleExpense(runtime, cashId, commandId);
    const read = second.waitForResponse(response => response.request().method() === 'GET' && response.url().endsWith(`/api/v2/accounting/commands/${commandId}`));
    await second.reload();
    expect((await read).status()).toBe(200);
    await expect.poll(async () => (await expenseIntent(second, commandId)).phase).toBe(JournalPhase.Resolved);
    // The late original tab must neither reopen Resolved nor resend its POST.
    await loss.lose();
    const projectionUrl = `**/api/v2/expenses/commands/${commandId}/projection`;
    let releaseProjection!: () => void;
    let projectionObserved!: () => void;
    const projectionGate = new Promise<void>(resolve => { releaseProjection = resolve; });
    const projectionSeen = new Promise<void>(resolve => { projectionObserved = resolve; });
    const holdProjection = async (route: Route) => {
      const upstream = await route.fetch({ maxRetries: 0, maxRedirects: 0 });
      projectionObserved();
      await projectionGate;
      await route.fulfill({ response: upstream });
    };
    await page.route(projectionUrl, holdProjection, { times: 1 });
    try {
      await expect(page.getByRole('button', { name: 'Consultar recibo', exact: true })).toBeEnabled();
      await page.getByRole('button', { name: 'Consultar recibo', exact: true }).click();
      await projectionSeen;
      // The stale tab has verified the receipt and is waiting for its real
      // authoritative projection. Persisted Resolved must not reopen meanwhile.
      expect((await expenseIntent(page, commandId)).phase).toBe(JournalPhase.Resolved);
      releaseProjection();
      await expect(page.getByRole('button', { name: 'Consultar recibo', exact: true })).toHaveCount(0);
    } finally {
      releaseProjection();
      if (!page.isClosed()) await page.unroute(projectionUrl, holdProjection);
    }
    expect(await expenseIntent(page, commandId)).toEqual({ ...original, phase: JournalPhase.Resolved });
    expect(firstPosts).toEqual([commandId]);
    expect(secondPosts).toEqual([]);
    expect(await runtime.database.facts(cashId)).toEqual(before);
  } finally { await loss.dispose(); await second.close(); }
});

test('RELEASE_PENDING: lost response survives reload and stopped worker, blocks closing and recovers the same durable command', async ({ runtime, page }) => {
  const staff = await runtime.database.seed(StaffRole.Cashier);
  await new StaffSessionPage(page).login(staff);
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId);
  const cashId = await cash.open('0');
  await page.goto('/ticket');
  await expect.poll(async () => Number(await page.getByLabel('Precio', { exact: true }).inputValue())).toBeGreaterThan(100);
  await page.getByLabel('Descuento', { exact: true }).fill('0');
  await page.getByLabel('Importe efectivo', { exact: true }).fill('1');
  const reserveRequest = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith('/api/v2/sales/reservations'));
  await page.getByRole('button', { name: 'Reservar y cobrar', exact: true }).click();
  const reservation = (await reserveRequest).postDataJSON();
  const operationId = String(reservation['operationId']);
  await expect(page.getByText(new RegExp(PaymentCoverage.Partial.label), { exact: false })).toBeVisible();
  await expect.poll(async () => (await journal(page)).every(entry => entry.phase === JournalPhase.Resolved)).toBe(true);
  await page.goto('/ventas');
  await page.locator('article').filter({ hasText: operationId }).getByRole('button', { name: 'Reabrir', exact: true }).click();
  await page.getByLabel('Motivo de operación', { exact: true }).fill('reversa antes de liberar');
  await page.getByRole('button', { name: /^Reversar pago \d+$/ }).click();
  await page.getByRole('dialog', { name: 'Confirmar reversa' }).getByRole('button', { name: 'Confirmar reversa', exact: true }).click();
  await expect(page.getByText(new RegExp(PaymentCoverage.Unpaid.label), { exact: false })).toBeVisible();
  await expect.poll(async () => (await journal(page)).every(entry => entry.phase === JournalPhase.Resolved)).toBe(true);
  // Stop the owned JAR and restart with the production worker switch disabled.
  // The database remains intact; no projection or delivery row is edited.
  await runtime.restartBackend(false);
  const posts: string[] = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith(`/api/v2/sales/${operationId}/release`)) posts.push(request.postDataJSON().commandId);
  });
  const loss = new DctResponseLoss(page, `**/api/v2/sales/${operationId}/release`);
  await loss.install();
  const sale = async () => (await runtime.database.query('SELECT * FROM sale_state_projection WHERE operation_id=$1', [operationId]))[0];
  const evidence = async () => ({
    admissions: await runtime.database.query('SELECT * FROM sale_command_admission_receipts WHERE operation_id=$1 ORDER BY command_id', [operationId]),
    outbox: await runtime.database.query('SELECT * FROM storecore_outbox_commands WHERE operation_id=$1 ORDER BY id', [operationId]),
    intents: await runtime.database.query('SELECT * FROM sale_intents WHERE operation_id=$1', [operationId]),
  });
  try {
    await page.getByLabel('Motivo de operación', { exact: true }).fill('liberar con worker detenido');
    await page.getByRole('button', { name: 'Liberar reserva', exact: true }).click();
    await page.getByRole('dialog', { name: 'Liberar reserva' }).getByRole('button', { name: 'Liberar reserva', exact: true }).click();
    const body = await loss.admitted;
    const commandId = String(body['commandId']);
    expect(DurableSaleState.fromWire((await sale()).status)).toBe(DurableSaleState.ReleasePending);
    const before = await evidence();
    expect(before.admissions).toHaveLength(2); // reserve and release
    expect(before.outbox).toHaveLength(2);
    expect(before.intents).toHaveLength(1);
    const release = before.admissions.find(row => SaleCommandKind.fromWire(row.kind) === SaleCommandKind.Release)!;
    expect(release.command_id).toBe(commandId);
    expect(Number(release.actor_id)).toBe(staff.id);
    expect(Number(release.cash_session_id)).toBe(cashId);
    const releaseOutbox = before.outbox.find(row => Number(row.id) === Number(release.outbox_id))!;
    expect(DurableCommandKind.fromWire(releaseOutbox.operation_kind)).toBe(DurableCommandKind.Release);
    expect(releaseOutbox).toMatchObject({ client_instance_id: body['clientInstanceId'], device_id: body['deviceId'], sale_id: body['saleId'], operation_id: operationId });
    expect(await runtime.database.query('SELECT id FROM outbox_delivery_attempts WHERE command_id=$1', [release.outbox_id])).toHaveLength(0);
    const original = (await journal(page)).find(entry => entry.command.commandId === commandId)!;
    expect(original.phase).toBe(JournalPhase.AwaitingReceipt);
    expect(original.family).toBe(JournalFamily.Sale);
    expect(original.command.kind).toBe(SaleCommandKind.Release);
    expect(original.command.body).toEqual(body);
    await loss.lose();
    const read = page.waitForResponse(response => response.request().method() === 'GET' && response.url().endsWith(`/api/v2/sales/commands/${commandId}`));
    await page.reload();
    expect((await read).status()).toBe(200);
    await expect.poll(async () => (await journal(page)).find(entry => entry.command.commandId === commandId)?.phase).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);
    await page.locator('article').filter({ hasText: operationId }).getByRole('button', { name: 'Reabrir', exact: true }).click();
    await expect(page.getByRole('heading', { name: DurableSaleState.ReleasePending.label, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Liberar reserva', exact: true })).toHaveCount(0);
    await cash.visit(runtime.terminalId);
    await page.getByLabel('Declarado', { exact: true }).fill('0');
    await page.getByLabel('Motivo de cierre', { exact: true }).fill('bloqueado hasta release terminal');
    const close = page.locator('form').filter({ has: page.getByLabel('Declarado', { exact: true }) }).getByRole('button', { name: 'Cerrar sesión', exact: true });
    await expect(close).toBeDisabled();
    const cashBefore = await runtime.database.facts(cashId);
    // Independently prove the real server fence, with the browser SID and CSRF.
    // It cannot create a close receipt/reconciliation or mutate accounting facts.
    const denied = await page.evaluate(async ({ cashId, commandId }) => {
      const csrf = await (await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })).json();
      const response = await fetch(`/api/v2/cash-sessions/${cashId}/close`, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf.data.csrfToken },
        body: JSON.stringify({ commandId, cashSessionId: cashId, declaredCash: 0, reason: 'pending release browser fence' }) });
      return { status: response.status, body: await response.json() };
    }, { cashId, commandId: crypto.randomUUID() });
    expect(denied.status).toBe(CashMutationOutcome.Conflict.httpStatus);
    expect(PosWireMapper.commandReceipt(denied.body, '').failure).toBe(CommandFailure.NonTerminalSale);
    const afterDenial = await runtime.database.facts(cashId);
    expect({ ...afterDenial, audit: [] }).toEqual({ ...cashBefore, audit: [] });
    expect(afterDenial.cash.status).toBe(CashSessionStatus.Open);
    expect(DurableSaleState.fromWire((await sale()).status)).toBe(DurableSaleState.ReleasePending);
    expect(await runtime.database.query('SELECT id FROM outbox_delivery_attempts WHERE command_id=$1', [release.outbox_id])).toHaveLength(0);
    expect(await evidence()).toEqual(before);
    await runtime.restartBackend(true);
    await expect.poll(async () => DurableSaleState.fromWire((await sale()).status)).toBe(DurableSaleState.Released);
    const terminalProjection = await page.evaluate(async operation => (await fetch(`/api/v1/sales/operations/${operation}`, { credentials: 'same-origin' })).json(), operationId);
    expect(PosWireMapper.durableDetail(terminalProjection, operationId)).toMatchObject({ valid: true, status: DurableSaleState.Released });
    // A browser restart rehydrates the exact unresolved sale command. This performs
    // a GET-only receipt/projection recovery after the worker reached terminal state.
    const terminalRead = page.waitForResponse(response => response.request().method() === 'GET' && response.url().endsWith(`/api/v2/sales/commands/${commandId}`));
    await page.reload();
    expect((await terminalRead).status()).toBe(200);
    await expect.poll(async () => (await journal(page)).find(entry => entry.command.commandId === commandId)?.phase).toBe(JournalPhase.Resolved);
    expect((await journal(page)).find(entry => entry.command.commandId === commandId)).toEqual({ ...original, phase: JournalPhase.Resolved });
    expect(await evidence()).toEqual(before);
    expect(posts).toEqual([commandId]);
    expect(await runtime.database.query('SELECT id FROM outbox_delivery_attempts WHERE command_id=$1', [release.outbox_id])).toHaveLength(1);
    expect(await runtime.database.query('SELECT inbox_id FROM storecore_inbox_applications WHERE command_id=$1', [release.outbox_id])).toHaveLength(1);
    await page.goto('/ventas');
    await page.locator('article').filter({ hasText: operationId }).getByRole('button', { name: 'Reabrir', exact: true }).click();
    await expect(page.getByRole('heading', { name: DurableSaleState.Released.label, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Liberar reserva', exact: true })).toHaveCount(0);
    await cash.visit(runtime.terminalId);
    await page.getByLabel('Declarado', { exact: true }).fill('0');
    await page.getByLabel('Motivo de cierre', { exact: true }).fill('release terminal comprobado');
    await expect(close).toBeEnabled();
    await cash.close(cashId, '0');
    expect((await runtime.database.facts(cashId)).cash.status).toBe(CashSessionStatus.Closed);
  } finally { await loss.dispose(); }
});

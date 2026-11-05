import { Page, TestInfo } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { test, expect, DctRuntime } from './fixtures/dct-runtime';
import { DctResponseLoss } from './fixtures/dct-response-loss';
import { journal } from './fixtures/dct-journal';
import { StaffSessionPage } from './pages/staff-session-page';
import { CashSessionPage } from './pages/cash-session-page';
import { CartPhase } from '../../src/app/core/domain/cart';
import { DurableSaleState } from '../../src/app/core/domain/durable-sale';
import { JournalPhase } from '../../src/app/core/domain/command-journal';
import { PaymentMethod, PaymentStatus, SaleStatus, StaffRole } from '../../src/app/core/domain/pos-types';
import { PaymentCoverage, TicketMoney } from '../../src/app/core/domain/ticket-transition';
import { SaleAdmissionFamily, SaleAdmissionRoute, SaleCommandKind, SaleFingerprintVersion, SaleReservationLineV2 } from '../../src/app/core/domain/sale-command';

interface ObservedMutation { readonly path: string; readonly body: Record<string, unknown>; }

function mutations(page: Page): ObservedMutation[] {
  const observed: ObservedMutation[] = [];
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (request.method() === 'POST' && /^\/api\/v[123]\/(sales|payments)(\/|$)/.test(path)) {
      observed.push({ path, body: request.postDataJSON() });
    }
  });
  return observed;
}

async function openCart(runtime: DctRuntime, page: Page): Promise<void> {
  await new StaffSessionPage(page).login(await runtime.database.seed(StaffRole.Cashier));
  const cash = new CashSessionPage(page);
  await cash.visit(runtime.terminalId);
  await cash.open('0');
  await page.goto('/ticket');
  const product = page.getByLabel('Producto', { exact: true });
  await expect(product).toBeVisible();
  await expect.poll(() => product.locator('option').count()).toBeGreaterThanOrEqual(2);
  // Exercise the native keyboard submit and the component's explicit focus handoff.
  await product.selectOption({ index: 0 });
  await product.focus();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Agregar al carrito', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('[data-cart-heading]')).toBeFocused();
  await expect(page.locator('.pos [aria-live="polite"]')).toContainText('1 unidades en el carrito.');
  await product.selectOption({ index: 1 });
  await page.getByRole('button', { name: 'Agregar al carrito', exact: true }).press('Enter');
  await expect(page.locator('[data-cart-heading]')).toBeFocused();
  await expect(page.locator('.cart-line')).toHaveCount(2);
  await expect(page.locator('.pos [aria-live="polite"]')).toContainText('2 unidades en el carrito.');
  for (const line of await page.locator('.cart-line').all()) {
    const name = (await line.locator('.line-product b').innerText()).trim();
    await expect(line.getByRole('spinbutton', { name: `Cantidad de ${name}`, exact: true })).toHaveValue('1');
    await expect(line.getByRole('button', { name: `Quitar ${name}`, exact: true })).toBeEnabled();
  }
}

async function checkpoint(page: Page, info: TestInfo, width: number, phase: CartPhase): Promise<void> {
  await expect(page.locator('.pos')).toHaveAttribute('aria-busy', 'false');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const directory = resolve(info.project.outputDir, 'screenshots');
  await mkdir(directory, { recursive: true });
  // Only the POS surface: no staff banner, login credentials, SID, storage or network payloads.
  await page.locator('.pos').screenshot({
    path: resolve(directory, `posc-cart-${width}-${phase.wire}.png`),
    animations: 'disabled', mask: [page.locator('.counter-context strong'), page.locator('.operation-summary details')],
  });
}

async function saleFacts(runtime: DctRuntime, operationId: string) {
  const values = [operationId];
  return {
    intents: await runtime.database.query('SELECT id FROM sale_intents WHERE operation_id=$1', values),
    sale: await runtime.database.query('SELECT id,status,gross_sales::text FROM sale_state_projection WHERE operation_id=$1', values),
    lines: await runtime.database.query(`SELECT variant_id,sku,quantity,original_unit_price::text,discount_amount::text,price_version
      FROM sale_lines WHERE sale_id=(SELECT id FROM sale_state_projection WHERE operation_id=$1) ORDER BY variant_id`, values),
    payments: await runtime.database.query(`SELECT id,payment_method,status,amount::text FROM payments
      WHERE sale_id=(SELECT id FROM sale_state_projection WHERE operation_id=$1) ORDER BY id`, values),
    paymentReceipts: await runtime.database.query(`SELECT command_id FROM accounting_command_receipts
      WHERE sale_id=(SELECT id FROM sale_state_projection WHERE operation_id=$1) ORDER BY recorded_at,command_id`, values),
    admissions: await runtime.database.query('SELECT command_id,kind FROM sale_command_admission_receipts WHERE operation_id=$1 ORDER BY accepted_at,command_id', values),
    contracts: await runtime.database.query(`SELECT m.* FROM sale_command_admission_contracts m JOIN sale_command_admission_receipts r USING(command_id)
      WHERE r.operation_id=$1 ORDER BY r.accepted_at,r.command_id`, values),
    outbox: await runtime.database.query('SELECT id,operation_kind FROM storecore_outbox_commands WHERE operation_id=$1 ORDER BY id', values),
    ledger: await runtime.database.query(`SELECT id,command_id,amount_delta::text FROM cash_ledger_events
      WHERE sale_id=(SELECT id FROM sale_state_projection WHERE operation_id=$1) ORDER BY id`, values),
  };
}

async function reloadWithoutMutation(page: Page, posts: ObservedMutation[], operationId: string): Promise<void> {
  const before = posts.slice();
  const projection = page.waitForResponse(response => response.request().method() === 'GET' &&
    response.url().endsWith(`/api/v1/sales/operations/${operationId}`));
  await page.reload();
  expect((await projection).status()).toBe(200);
  await expect(page.locator('.pos')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('.frozen-line')).toHaveCount(2);
  expect(posts).toEqual(before);
}

for (const width of [360, 768, 1280]) {
  test(`POSC-E A08/A11: real V3 two-product split payment survives reload at ${width}px`, async ({ runtime, page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await openCart(runtime, page);
    const posts = mutations(page);
    await checkpoint(page, info, width, CartPhase.Editing);
    const reserve = page.waitForResponse(response => response.request().method() === 'POST' &&
      response.url().endsWith(SaleAdmissionRoute.ReserveV3.wire));
    await page.getByRole('button', { name: 'Reservar productos', exact: true }).press('Enter');
    expect((await reserve).status()).toBe(202);
    expect(posts).toHaveLength(1);
    const body = posts[0].body;
    const operationId = String(body['operationId']);
    const lines = body['lines'] as SaleReservationLineV2[];
    expect(lines).toHaveLength(2);
    expect(new Set(lines.map(line => line.variantId)).size).toBe(2);
    const total = lines.reduce((sum, line) => sum + TicketMoney.fromDecimal(line.originalUnitPrice)!.cents * BigInt(line.quantity), 0n);
    const partial = TicketMoney.fromDecimal('1.00')!;
    expect(total).toBeGreaterThan(partial.cents);
    const remainderCents = total - partial.cents;
    const remainder = `${remainderCents / 100n}.${String(remainderCents % 100n).padStart(2, '0')}`;
    const amount = page.getByLabel('Importe', { exact: true });
    await expect(amount).toBeFocused();
    await expect(page.locator('.pos [aria-live="polite"]')).toHaveText('Reserva comprobada. El cobro está habilitado.');
    await expect(page.getByRole('button', { name: 'Confirmar venta', exact: true })).toHaveCount(0);
    let facts = await saleFacts(runtime, operationId);
    expect(facts.intents).toHaveLength(1);
    expect(facts.sale).toHaveLength(1);
    expect(DurableSaleState.fromWire(facts.sale[0].status)).toBe(DurableSaleState.Reserved);
    expect(facts.payments).toHaveLength(0);
    expect(facts.paymentReceipts).toHaveLength(0);
    expect(facts.ledger).toHaveLength(0);
    expect(facts.admissions).toHaveLength(1);
    expect(SaleCommandKind.fromWire(facts.admissions[0].kind)).toBe(SaleCommandKind.Reserve);
    expect(facts.outbox).toHaveLength(1);
    expect(facts.lines).toEqual([...lines].sort((a, b) => a.variantId.localeCompare(b.variantId)).map(line => ({
      variant_id: line.variantId, sku: line.sku, quantity: line.quantity, original_unit_price: line.originalUnitPrice,
      discount_amount: line.discountAmount, price_version: line.expectedPriceVersion,
    })));
    expect(facts.contracts).toHaveLength(1);
    expect(SaleAdmissionFamily.fromWire(facts.contracts[0].contract_family)).toBe(SaleAdmissionFamily.V3);
    expect(SaleFingerprintVersion.fromWire(facts.contracts[0].fingerprint_version)).toBe(SaleFingerprintVersion.AdmissionV2);
    expect(SaleAdmissionRoute.fromWire(facts.contracts[0].canonical_route)).toBe(SaleAdmissionRoute.ReserveV3);
    await reloadWithoutMutation(page, posts, operationId);
    expect(await saleFacts(runtime, operationId)).toEqual(facts);
    if (width === 1280) {
      // A08 combines a real owned-JAR PID restart with browser reload while
      // the multiline ticket is durable. Neither action may replay ReserveV3.
      await runtime.restartBackend();
      await reloadWithoutMutation(page, posts, operationId);
      expect(await saleFacts(runtime, operationId)).toEqual(facts);
      expect(posts).toHaveLength(1);
    }
    await expect(page.locator('[data-operation-notice]')).toContainText(PaymentCoverage.Unpaid.label);
    await checkpoint(page, info, width, CartPhase.Reserved);

    const method = page.getByLabel('Medio de pago', { exact: true });
    await method.selectOption({ label: PaymentMethod.Cash.label });
    await amount.fill('1.00');
    await expect(amount).toHaveAttribute('aria-describedby', 'payment-help');
    await page.getByRole('button', { name: 'Registrar cobro', exact: true }).press('Enter');
    await expect(page.locator('.pos [aria-live="polite"]')).toHaveText('Pago parcial comprobado. Ingresá el siguiente cobro.');
    await expect(amount).toBeFocused();
    expect(posts).toHaveLength(2);
    expect(posts[1].path).toBe('/api/v2/payments');
    await expect(page.getByRole('button', { name: 'Confirmar venta', exact: true })).toHaveCount(0);
    facts = await saleFacts(runtime, operationId);
    expect(facts.payments).toHaveLength(1);
    expect(facts.paymentReceipts.map(receipt => receipt.command_id)).toEqual([posts[1].body['commandId']]);
    expect(facts.ledger).toHaveLength(1);
    await reloadWithoutMutation(page, posts, operationId);
    expect(await saleFacts(runtime, operationId)).toEqual(facts);
    await expect(page.locator('[data-operation-notice]')).toContainText(PaymentCoverage.Partial.label);
    await expect(amount).toHaveValue(remainder);
    await expect(page.getByRole('region', { name: 'Historial de cobros' }).locator('p')).toHaveCount(1);
    await checkpoint(page, info, width, CartPhase.AwaitingPayment);

    await method.selectOption({ label: PaymentMethod.Card.label });
    await amount.fill(remainder);
    await page.getByRole('button', { name: 'Registrar cobro', exact: true }).press('Enter');
    await expect(page.locator('.pos [aria-live="polite"]')).toHaveText('Pago completo comprobado. La venta está lista para confirmar.');
    const confirm = page.getByRole('button', { name: 'Confirmar venta', exact: true });
    await expect(confirm).toBeEnabled();
    expect(posts).toHaveLength(3);
    expect(posts[2].path).toBe('/api/v2/payments');
    facts = await saleFacts(runtime, operationId);
    expect(facts.payments).toHaveLength(2);
    expect(facts.paymentReceipts.map(receipt => receipt.command_id)).toEqual([posts[1].body['commandId'], posts[2].body['commandId']]);
    expect(facts.payments.map(payment => PaymentMethod.fromWire(payment.payment_method))).toEqual([PaymentMethod.Cash, PaymentMethod.Card]);
    expect(facts.payments.every(payment => PaymentStatus.fromWire(payment.status) === PaymentStatus.Captured)).toBe(true);
    expect(facts.payments.map(payment => payment.amount)).toEqual(['1.00', remainder]);
    expect(facts.admissions).toHaveLength(1); // Covering the total never commits implicitly.
    await confirm.press('Enter');
    await expect(page.getByRole('button', { name: 'Iniciar nueva venta', exact: true })).toBeEnabled();
    await expect(page.locator('.pos [aria-live="polite"]')).toHaveText('Operación finalizada y comprobada.');
    expect(posts).toHaveLength(4);
    expect(posts[3].path).toBe(SaleAdmissionRoute.CommitV2.path(operationId));
    expect(new Set(posts.map(post => post.body['commandId'])).size).toBe(4);
    facts = await saleFacts(runtime, operationId);
    expect(DurableSaleState.fromWire(facts.sale[0].status)).toBe(DurableSaleState.Committed);
    expect(facts.admissions.map(row => SaleCommandKind.fromWire(row.kind))).toEqual([SaleCommandKind.Reserve, SaleCommandKind.Commit]);
    expect(facts.contracts).toHaveLength(2);
    expect(facts.outbox).toHaveLength(2);
    expect(facts.ledger).toHaveLength(2);
    expect(facts.lines).toHaveLength(2);
    await reloadWithoutMutation(page, posts, operationId);
    await expect(page.getByRole('button', { name: 'Iniciar nueva venta', exact: true })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Registrar cobro', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Confirmar venta', exact: true })).toHaveCount(0);
    await expect(page.locator('[data-operation-notice]')).toContainText(SaleStatus.Committed.label);
    expect(await saleFacts(runtime, operationId)).toEqual(facts);
    await expect.poll(async () => (await journal(page)).every(entry => entry.phase === JournalPhase.Resolved)).toBe(true);
    expect(posts).toHaveLength(4);
    await checkpoint(page, info, width, CartPhase.Completed);
  });
}

test('POSC-E: ReserveV3 response loss reloads the original admission and two lines with GET only', async ({ runtime, page }) => {
  await openCart(runtime, page);
  const posts = mutations(page);
  const loss = new DctResponseLoss(page, `**${SaleAdmissionRoute.ReserveV3.wire}`);
  await loss.install();
  try {
    await page.getByRole('button', { name: 'Reservar productos', exact: true }).click();
    const body = await loss.admitted;
    const commandId = String(body['commandId']);
    const operationId = String(body['operationId']);
    const intent = (await journal(page)).find(entry => entry.command.commandId === commandId)!;
    expect(intent.phase).toBe(JournalPhase.AwaitingReceipt);
    expect(intent.command.body).toEqual(body);
    await expect.poll(async () => DurableSaleState.fromWire((await saleFacts(runtime, operationId)).sale[0]?.status)).toBe(DurableSaleState.Reserved);
    const before = await saleFacts(runtime, operationId);
    expect(before.lines).toHaveLength(2);
    expect(before.admissions).toHaveLength(1);
    expect(before.outbox).toHaveLength(1);
    await loss.lose();
    await expect(page.getByRole('button', { name: 'Consultar admisión', exact: true })).toBeEnabled();
    const receipt = page.waitForResponse(response => response.request().method() === 'GET' &&
      response.url().endsWith(`/api/v3/sales/commands/${commandId}`));
    await reloadWithoutMutation(page, posts, operationId);
    expect((await receipt).status()).toBe(200);
    await expect.poll(async () => (await journal(page)).find(entry => entry.command.commandId === commandId)?.phase).toBe(JournalPhase.Resolved);
    await expect(page.getByLabel('Importe', { exact: true })).toBeEnabled();
    expect(posts).toHaveLength(1);
    expect(posts[0].body).toEqual(body);
    expect(await saleFacts(runtime, operationId)).toEqual(before);
  } finally { await loss.dispose(); }
});

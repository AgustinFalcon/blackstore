import { expect, Page, Route, test } from '@playwright/test';

const saleId = 'Sale/Aa-01';
const operationId = '00000000-0000-4000-8000-000000000002';
const clientInstanceId = '00000000-0000-4000-8000-000000000004';

test.beforeEach(async ({ page }) => mockOperationalReads(page));

test('opaque ticket deep link survives reload and remains read-only', async ({ page }) => {
  const writes: string[] = [];
  page.on('request', request => { if (request.method() !== 'GET') writes.push(`${request.method()} ${request.url()}`); });

  await page.goto('/ticket/id%3ASale%2FAa-01');
  await expect(page.getByRole('heading', { name: 'Detalle de venta' })).toBeVisible();
  await expect(page.getByText('Evidencia comprobada')).toBeVisible();
  await expect(page.getByText(saleId, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /confirmar|cobrar|reversar|liberar/i })).toHaveCount(0);

  await page.reload();
  await expect(page.getByRole('heading', { name: 'Detalle de venta' })).toBeVisible();
  await expect(page.getByText('Pantalla no disponible')).toHaveCount(0);
  expect(writes).toEqual([]);
});

test('dot-only opaque ids retain their route identity before lookup', async ({ page }) => {
  await page.goto('/ticket/id%3A..');
  await expect(page.getByRole('heading', { name: 'Detalle de venta' })).toBeVisible();
  await expect(page).toHaveURL(/\/ticket\/id(?::|%3A)\.\.$/i);
  await expect(page.getByText('Pantalla no disponible')).toHaveCount(0);
});

test('dedicated cash close hides opening and expense controls on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 760 });
  await page.goto('/caja/cierre');
  await expect(page.getByRole('heading', { name: 'Cierre de caja' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Abrir sesión' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Registrar gasto' })).toHaveCount(0);
  await expect(page.locator('form').getByRole('button', { name: 'Cerrar sesión' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('operational route focus does not override a control reached after navigation', async ({ page }) => {
  await page.goto('/ticket/id%3ASale%2FAa-01');
  await page.getByRole('link', { name: 'Reportes' }).click();
  const localDate = page.getByLabel('Fecha local');
  await localDate.focus();
  await expect(localDate).toBeFocused();
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(localDate).toBeFocused();
});

async function mockOperationalReads(page: Page): Promise<void> {
  await page.route('**/api/**', async route => {
    const request = route.request();
    if (request.method() !== 'GET') {
      await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify(envelope(null, 405)) });
      return;
    }
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/auth/session')) return json(route, envelope({ staff: { id: 7, displayName: 'Titular ODR', role: 'OWNER' } }));
    if (path.endsWith('/auth/csrf')) return json(route, envelope({ csrfToken: 'odr-browser-token' }));
    if (path.endsWith('/health')) return json(route, envelope({ service: 'blackstore', boundedContext: 'pos', storeCoreIntegrationEnabled: false }));
    if (path.endsWith('/workspace')) return json(route, envelope({ terminalId: 1, cashierId: 7, persistence: 'postgresql' }));
    if (path.endsWith('/cash-sessions')) return json(route, envelope([{ id: 5, terminalId: 1, cashierId: 7, status: 'OPEN', openingCash: 100 }]));
    if (path.endsWith(`/sales/operations/${operationId}`)) return json(route, envelope(detail()));
    if (path.endsWith('/sales')) return json(route, envelope({ items: [summary()], nextCursor: null }));
    if (path.endsWith('/catalog')) return json(route, envelope([]));
    return json(route, envelope(null));
  });
}

function summary() {
  return { operationId, clientInstanceId, deviceId: 'counter-1', saleId, cashSessionId: 5, cashierId: 7,
    createdBy: 7, status: 'RESERVED', totalAmount: 10 };
}
function detail() {
  return { ...summary(), evidenceValid: true, pendingAmount: 10, paymentCoverage: 'UNPAID', hasPaymentHistory: false,
    blocked: false, retired: false, receipt: 'receipt-1', reservationRef: 'reservation-1', pendingCommand: null,
    lines: [{ variantId: 'variant-1', sku: 'SKU-1', productName: 'Artículo', quantity: 1, originalUnitPrice: 10, priceVersion: 'v1', totalAmount: 10 }],
    payments: [], allowedActions: [] };
}
function envelope<T>(data: T, code = 200) {
  return { code, traceId: 'odr-browser-trace', data, message: null, errorCode: null, retryable: null };
}
async function json(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

import { expect, Page } from '@playwright/test';

/** Explicitly accepts the backend-owned local fixture for this actor/catalog version. */
export async function acceptLocalCatalog(page: Page): Promise<void> {
  const acceptance = page.getByRole('button', { name: 'Aceptar simulador para esta venta', exact: true });
  await expect(acceptance).toBeVisible();
  await acceptance.click();
  await expect(page.getByText('Aceptado para esta sesión', { exact: true })).toBeVisible();
}

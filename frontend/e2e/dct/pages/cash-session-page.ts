import { Page, expect } from '@playwright/test';
import { CashMutationOutcome } from '../../../src/app/core/domain/pos-types';

export class CashSessionPage {
  constructor(readonly page: Page) {}
  async visit(terminal: number) {
    await this.page.goto('/caja');
    await expect(this.page.getByText('Cargando puesto de trabajo…', { exact: true })).toHaveCount(0);
    await this.page.getByLabel('Terminal', { exact: true }).fill(String(terminal));
  }
  private response(path: string) { return this.page.waitForResponse(r => r.url().endsWith(path) && r.request().method() === 'POST'); }
  async open(amount: string): Promise<number> {
    await this.page.getByLabel('Apertura', { exact: true }).fill(amount);
    const pending = this.response('/api/v1/cash-sessions');
    await this.page.getByRole('button', { name: 'Abrir sesión', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(200);
    const session = this.page.getByText(/^Sesión \d+ en terminal \d+/);
    await expect(session).toBeVisible();
    const match = /^Sesión (\d+) en terminal/.exec((await session.textContent()) ?? '');
    expect(match).not.toBeNull();
    return Number(match![1]);
  }
  async expense(amount: string, outcome = CashMutationOutcome.Applied) {
    await this.page.getByLabel('Gasto', { exact: true }).fill(amount);
    const pending = this.response('/api/v1/expenses');
    await this.page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(outcome.httpStatus);
    await expect(this.page.getByText(outcome === CashMutationOutcome.Applied ? 'Gasto registrado' : outcome.label, { exact: true })).toBeVisible();
  }
  async close(cashId: number, amount: string) {
    await this.page.getByLabel('Declarado', { exact: true }).fill(amount);
    const pending = this.response(`/api/v1/cash-sessions/${cashId}/close`);
    await this.page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(CashMutationOutcome.Applied.httpStatus);
    await expect(this.page.getByText(`Sesión ${cashId} cerrada`, { exact: true })).toBeVisible();
  }
  async expectClosedControlsAbsent() {
    await expect(this.page.getByRole('button', { name: 'Registrar gasto', exact: true })).toHaveCount(0);
    await expect(this.page.getByRole('button', { name: 'Cerrar sesión', exact: true })).toHaveCount(0);
  }
  async expectClosedWorkstation(terminal: number, cashier: number) {
    await expect(this.page.getByText('Cargando puesto de trabajo…', { exact: true })).toHaveCount(0);
    const terminalInput = this.page.getByLabel('Terminal', { exact: true });
    await terminalInput.fill(String(terminal));
    await expect(terminalInput).toHaveValue(String(terminal));
    await expect(this.page.getByLabel('Cajero', { exact: true })).toHaveValue(String(cashier));
    await expect(this.page.getByText('Sin caja activa para la terminal y el cajero solicitados', { exact: true })).toBeVisible();
    await this.expectClosedControlsAbsent();
  }
}

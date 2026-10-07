import { Page, expect } from '@playwright/test';
import { CashMutationOutcome } from '../../../src/app/core/domain/pos-types';
import { PosWireMapper } from '../../../src/app/core/infrastructure/pos-wire-mapper';

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
    const session = PosWireMapper.cashMutationSession(await response.json());
    expect(session).not.toBeNull();
    return session!.id;
  }
  async expense(amount: string, outcome = CashMutationOutcome.Applied) {
    await this.page.getByLabel('Gasto', { exact: true }).fill(amount);
    const pending = this.response('/api/v1/expenses');
    await this.page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    const response = await pending;
    expect(PosWireMapper.cashMutationOutcome(await response.json(), response.status())).toBe(outcome);
  }
  async close(cashId: number, amount: string) {
    await this.page.getByLabel('Declarado', { exact: true }).fill(amount);
    const pending = this.response(`/api/v1/cash-sessions/${cashId}/close`);
    await this.page.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    const response = await pending;
    expect(PosWireMapper.cashMutationOutcome(await response.json(), response.status())).toBe(CashMutationOutcome.Applied);
  }
  async expectClosedControlsAbsent() {
    await expect(this.page.getByRole('button', { name: 'Registrar gasto', exact: true })).toHaveCount(0);
    await expect(this.page.getByRole('button', { name: 'Cerrar sesión', exact: true })).toHaveCount(0);
  }
}

import { Page, expect } from '@playwright/test';
import { CashMutationOutcome } from '../../../src/app/core/domain/pos-types';
import { CommandOutcome, CommandFailure } from '../../../src/app/core/domain/accounting-command';
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
    const pending = this.response('/api/v2/cash-sessions');
    await this.page.getByRole('button', { name: 'Abrir sesión', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(200);
    expect(PosWireMapper.commandReceipt(await response.json(), response.request().postDataJSON().commandId).outcome).toBe(CommandOutcome.Committed);
    const session = this.page.getByText(/^\s*Sesión\s+\d+\s+en\s+terminal\s+\d+/);
    await expect(session).toBeVisible();
    const rendered = ((await session.textContent()) ?? '').replace(/\s+/g, ' ').trim();
    const match = /^Sesión (\d+) en terminal/.exec(rendered);
    expect(match).not.toBeNull();
    return Number(match![1]);
  }
  async expense(amount: string, failure = CommandFailure.None) {
    await this.page.getByLabel('Gasto', { exact: true }).fill(amount);
    const pending = this.response('/api/v2/expenses');
    await this.page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(failure === CommandFailure.None ? 200 : 409);
    const receipt = PosWireMapper.commandReceipt(await response.json(), response.request().postDataJSON().commandId);
    expect(receipt.failure).toBe(failure);
    if (failure === CommandFailure.None) expect(receipt.outcome).toBe(CommandOutcome.Committed);
    await expect(this.page.getByText(failure === CommandFailure.None ? CommandOutcome.Committed.label : failure.label, { exact: true }).first()).toBeVisible();
  }
  async close(cashId: number, amount: string) {
    await this.page.getByLabel('Declarado', { exact: true }).fill(amount);
    const pending = this.response(`/api/v2/cash-sessions/${cashId}/close`);
    const closeForm = this.page.locator('form').filter({ has: this.page.getByLabel('Declarado', { exact: true }) });
    await closeForm.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(CashMutationOutcome.Applied.httpStatus);
    expect(PosWireMapper.commandReceipt(await response.json(), response.request().postDataJSON().commandId).outcome).toBe(CommandOutcome.Committed);
    await this.expectClosedControlsAbsent();
  }
  async expectClosedControlsAbsent() {
    await expect(this.page.getByRole('button', { name: 'Registrar gasto', exact: true })).toHaveCount(0);
    const cashCloseForm = this.page.locator('form').filter({ has: this.page.getByLabel('Declarado', { exact: true }) });
    await expect(cashCloseForm.getByRole('button', { name: 'Cerrar sesión', exact: true })).toHaveCount(0);
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

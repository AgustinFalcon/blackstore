import { Page, expect } from '@playwright/test';
import { CashMutationOutcome } from '../../../src/app/core/domain/pos-types';
import { CommandOutcome, CommandFailure, CommandReceipt } from '../../../src/app/core/domain/accounting-command';
import { PosWireMapper } from '../../../src/app/core/infrastructure/pos-wire-mapper';

export class CashSessionPage {
  constructor(readonly page: Page) {}
  async visit(terminal: number) {
    await this.page.goto('/caja');
    await expect(this.page.getByText('Cargando puesto de trabajo…', { exact: true })).toHaveCount(0);
    await this.page.getByLabel('Terminal', { exact: true }).fill(String(terminal));
  }
  private response(path: string) {
    // Capture request correlation/status immediately; never ask CDP to retain a
    // consumed POST body while click auto-waits and the UI refreshes projections.
    return this.page.waitForResponse(r => r.url().endsWith(path) && r.request().method() === 'POST')
      .then(response => ({ status: response.status(), commandId: response.request().postDataJSON().commandId as unknown }));
  }
  private async committedReceipt(response: { status: number; commandId: unknown }): Promise<CommandReceipt> {
    expect(response.status).toBe(CashMutationOutcome.Applied.httpStatus);
    expect(PosWireMapper.uuid(response.commandId)).toBe(true);
    const commandId = response.commandId as string;
    // Read the durable receipt with the real Chromium SID, in Chromium itself.
    // This GET has no mutation/retry semantics and its JSON never depends on CDP
    // response-body retention. There is no fallback POST or swallowed read error.
    const observation = await this.page.evaluate(async id => {
      const response = await fetch(`/api/v2/accounting/commands/${encodeURIComponent(id)}`, { credentials: 'same-origin', cache: 'no-store' });
      return { status: response.status, cacheControl: response.headers.get('cache-control'), body: await response.json() };
    }, commandId);
    expect(observation.status).toBe(200);
    expect(new Set((observation.cacheControl ?? '').split(',').map(value => value.trim()).filter(Boolean))).toEqual(new Set(['no-store']));
    const receipt = PosWireMapper.commandReceipt(observation.body, commandId);
    expect(receipt.outcome).toBe(CommandOutcome.Committed);
    expect(receipt.failure).toBe(CommandFailure.None);
    expect(receipt.commandId).toBe(commandId);
    return receipt;
  }
  async open(amount: string): Promise<number> {
    await this.page.getByLabel('Apertura', { exact: true }).fill(amount);
    const pending = this.response('/api/v2/cash-sessions');
    await this.page.getByRole('button', { name: 'Abrir sesión', exact: true }).click();
    const response = await pending;
    const receipt = await this.committedReceipt(response);
    const session = this.page.getByText(/^\s*Sesión\s+\d+\s+en\s+terminal\s+\d+/);
    await expect(session).toBeVisible();
    const rendered = ((await session.textContent()) ?? '').replace(/\s+/g, ' ').trim();
    const match = /^Sesión (\d+) en terminal/.exec(rendered);
    expect(match).not.toBeNull();
    const cashId = Number(match![1]);
    expect(receipt.cashSessionId).toBe(cashId);
    return cashId;
  }
  async expense(amount: string) {
    await this.page.getByLabel('Gasto', { exact: true }).fill(amount);
    const pending = this.response('/api/v2/expenses');
    await this.page.getByRole('button', { name: 'Registrar gasto', exact: true }).click();
    const response = await pending;
    const receipt = await this.committedReceipt(response);
    expect(receipt.expenseId).not.toBeNull();
    expect(receipt.settlementId).not.toBeNull();
    await expect(this.page.getByText(CommandOutcome.Committed.label, { exact: true }).first()).toBeVisible();
  }
  async close(cashId: number, amount: string) {
    await this.page.getByLabel('Declarado', { exact: true }).fill(amount);
    const pending = this.response(`/api/v2/cash-sessions/${cashId}/close`);
    const closeForm = this.page.locator('form').filter({ has: this.page.getByLabel('Declarado', { exact: true }) });
    await closeForm.getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    const response = await pending;
    const receipt = await this.committedReceipt(response);
    expect(receipt.cashSessionId).toBe(cashId);
    expect(receipt.closeSnapshot).not.toBeNull();
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

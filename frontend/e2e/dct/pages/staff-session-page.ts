import { Page, expect } from '@playwright/test';
import { TestStaff } from '../fixtures/dct-database';

export class StaffSessionPage {
  constructor(private readonly page: Page) {}
  async login(staff: TestStaff) {
    await this.page.goto('/sesion');
    await this.page.getByLabel('Usuario', { exact: true }).fill(staff.login);
    try { await this.page.getByLabel('Contraseña', { exact: true }).fill(staff.password); }
    catch { throw new Error('DCT password form control unavailable'); }
    const response = this.page.waitForResponse(r => r.url().endsWith('/api/v1/auth/login') && r.request().method() === 'POST');
    await this.page.getByRole('button', { name: 'Ingresar', exact: true }).click();
    expect((await response).status()).toBe(200);
    await expect(this.page).not.toHaveURL(/\/sesion$/);
  }
}

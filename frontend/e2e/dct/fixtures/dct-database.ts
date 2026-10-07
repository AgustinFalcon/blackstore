import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { hash } from 'bcryptjs';
import { CashSessionStatus, StaffRole } from '../../../src/app/core/domain/pos-types';
import { DctAuditEvent } from './dct-audit-event';

export interface TestStaff { id: number; login: string; password: string; role: StaffRole }

/** Owns exactly one disposable container, never a pre-existing database. */
export class DctDatabase {
  private containerId?: string;
  private readonly containerName = `blackstore-dct-${randomUUID()}`;
  private acquisitionAttempted = false;
  private client?: Client;
  readonly password = randomUUID();
  port = 0;
  private docker(...args: string[]): string {
    try {
      return execFileSync('docker', args, { encoding: 'utf8', timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, POSTGRES_PASSWORD: this.password } }).trim();
    } catch (error) {
      const stderr = (error as { stderr?: Buffer | string }).stderr?.toString() ?? 'Docker command could not run';
      throw new Error(`DCT Docker ${args[0]} failed: ${stderr.replaceAll(this.password, '[redacted]')}`);
    }
  }
  async start(): Promise<void> {
    console.log(`DCT owner=dct-browser revision=${process.env['GITHUB_SHA'] ?? 'local-uncommitted'} acquiring own PostgreSQL 16`);
    this.acquisitionAttempted = true;
    this.containerId = this.docker('run', '--detach', '--rm', '--name', this.containerName, '--label', 'blackstore.test.owner=dct-browser',
      '--publish', '127.0.0.1::5432', '--env', 'POSTGRES_DB=dct_browser', '--env', 'POSTGRES_USER=dct_admin',
      '--env', 'POSTGRES_PASSWORD', 'postgres:16-alpine');
    const binding = JSON.parse(this.docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', this.containerId));
    this.port = Number(binding['5432/tcp'][0].HostPort);
    console.log(`DCT PostgreSQL container=${this.containerId} loopbackPort=${this.port}`);
    for (let attempt = 0; attempt < 100; attempt++) {
      const client = new Client({ host: '127.0.0.1', port: this.port, database: 'dct_browser', user: 'dct_admin', password: this.password, connectionTimeoutMillis: 1000 });
      try {
        await client.connect();
        const version = (await client.query('SHOW server_version_num')).rows[0].server_version_num;
        if (Math.floor(Number(version) / 10000) !== 16) throw new Error('DCT requires PostgreSQL major 16');
        this.client = client; return;
      }
      catch { await client.end().catch(() => {}); await new Promise(resolve => setTimeout(resolve, 300)); }
    }
    throw new Error('Owned PostgreSQL 16 did not become ready');
  }
  async query(sql: string, values: unknown[] = []) { return (await this.client!.query(sql, values)).rows; }
  async seed(role: StaffRole): Promise<TestStaff> {
    const login = `dct-${randomUUID()}`;
    const password = randomUUID();
    const rows = await this.query('INSERT INTO staff_users(login,display_name,password_hash,role_code,active) VALUES($1,$2,$3,$4,true) RETURNING id',
      [login, 'DCT browser staff', await hash(password, 10), role.wire]);
    return { id: Number(rows[0].id), login, password, role };
  }
  async terminal(): Promise<number> {
    return Number((await this.query('INSERT INTO terminals(terminal_code) VALUES($1) RETURNING id', [`DCT-${randomUUID()}`]))[0].id);
  }
  async facts(cashId: number) {
    const cash = (await this.query('SELECT * FROM cash_session_projection WHERE id=$1', [cashId]))[0];
    return {
      cash: { ...cash, status: CashSessionStatus.fromWire(cash.status) },
      expenses: await this.query('SELECT id,amount::text,created_by,payment_method FROM expenses WHERE cash_session_id=$1 ORDER BY id', [cashId]),
      audit: (await this.query("SELECT id,event_type,actor_id,aggregate_id,payload_redacted,occurred_at FROM audit_events WHERE (aggregate_type='cash_session' AND aggregate_id=$1) OR (aggregate_type='expense' AND aggregate_id IN (SELECT id FROM expenses WHERE cash_session_id=$1)) ORDER BY id", [cashId]))
        .map(row => ({ ...row, event_type: DctAuditEvent.fromWire(row.event_type) })),
    };
  }
  async stop(): Promise<void> {
    try { await this.client?.end(); }
    finally {
      // Recover an unknown run outcome only by the unique name allocated before acquisition.
      if (!this.containerId && this.acquisitionAttempted) {
        try {
          const detail = JSON.parse(this.docker('inspect', this.containerName))[0];
          if (detail.Config.Labels['blackstore.test.owner'] !== 'dct-browser') throw new Error('DCT resource ownership mismatch');
          this.containerId = detail.Id;
        } catch { /* creation failed or daemon unreachable; the acquisition error remains visible */ }
      }
      if (this.containerId) { this.docker('rm', '--force', this.containerId); console.log(`DCT teardown container=${this.containerId} PASS`); this.containerId = undefined; }
    }
  }
}

import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { hash } from 'bcryptjs';
import { CashSessionStatus, StaffRole } from '../../../src/app/core/domain/pos-types';
import { DctAuditEvent } from './dct-audit-event';
import { AccountingCoverage, CommandOutcome } from '../../../src/app/core/domain/accounting-command';
import { ReconciliationOutcome } from '../../../src/app/core/domain/accounting-report';
import { DctLedgerKind } from './dct-ledger-kind';
import { AccountingLifecycleState } from '../../../src/app/core/domain/accounting-lifecycle';

export interface TestStaff { id: number; login: string; password: string; displayName: string; role: StaffRole }

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
    const displayName = 'DCT browser staff';
    const rows = await this.query('INSERT INTO staff_users(login,display_name,password_hash,role_code,active) VALUES($1,$2,$3,$4,true) RETURNING id',
      [login, displayName, await hash(password, 10), role.wire]);
    return { id: Number(rows[0].id), login, password, displayName, role };
  }
  async terminal(): Promise<number> {
    return Number((await this.query('INSERT INTO terminals(terminal_code) VALUES($1) RETURNING id', [`DCT-${randomUUID()}`]))[0].id);
  }
  async provision(terminalId: number, clientInstanceId: string): Promise<void> {
    await this.query('BEGIN');
    try {
      await this.query(`INSERT INTO pos_terminal_context(terminal_id,client_instance_id,device_id,provisioned_by_ref,installation_evidence_ref)
        VALUES($1,$2,$3,'isolated-dct-fixture','isolated-pg16-browser-v11')`, [terminalId, clientInstanceId, 'dct-browser-device']);
      await this.query('UPDATE accounting_runtime SET state=$1,accounting_activation_at=clock_timestamp() WHERE singleton', [AccountingLifecycleState.Active.wire]);
      await this.query('COMMIT');
    } catch (error) { await this.query('ROLLBACK'); throw error; }
  }
  async facts(cashId: number) {
    const cash = (await this.query('SELECT * FROM cash_session_projection WHERE id=$1', [cashId]))[0];
    return {
      cash: { ...cash, status: CashSessionStatus.fromWire(cash.status) },
      expenses: await this.query('SELECT id,amount::text,created_by,payment_method FROM expenses WHERE cash_session_id=$1 ORDER BY id', [cashId]),
      receipts: (await this.query('SELECT * FROM accounting_command_receipts WHERE cash_session_id=$1 ORDER BY recorded_at,command_id', [cashId]))
        .map(row => ({ ...row, outcome: CommandOutcome.fromWire(row.outcome) })),
      ledger: (await this.query('SELECT * FROM cash_ledger_events WHERE cash_session_id=$1 ORDER BY local_sequence', [cashId]))
        .map(row => ({ ...row, event_type: DctLedgerKind.fromWire(row.event_type) })),
      settlements: await this.query('SELECT * FROM expense_settlements WHERE cash_session_id=$1 ORDER BY id', [cashId]),
      reconciliation: (await this.query('SELECT * FROM cash_reconciliations WHERE cash_session_id=$1', [cashId]))
        .map(row => ({ ...row, outcome: ReconciliationOutcome.fromWire(row.outcome), coverage: AccountingCoverage.fromWire(row.coverage) })),
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

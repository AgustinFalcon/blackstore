import { test as base, expect } from '@playwright/test';
import { spawn, ChildProcess, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { DctDatabase } from './dct-database';

async function requireFreePort(port: number) {
  const server = createServer();
  try {
    await new Promise<void>((ok, fail) => { server.once('error', fail); server.listen(port, ok); });
  } finally { if (server.listening) await new Promise<void>(ok => server.close(() => ok())); }
}

class ManagedProcess {
  private child?: ChildProcess;
  get pid() { return this.child?.pid; }
  start(command: string, args: string[], cwd: string, env = process.env) {
    this.child = spawn(command, args, { cwd, env, stdio: 'ignore', windowsHide: true, detached: process.platform !== 'win32' });
    // Avoid an unhandled spawn error; readiness reports failure without dumping credentials.
    this.child.on('error', () => {});
    console.log(`DCT owned process pid=${this.child.pid} command=${command === process.execPath ? 'Angular' : 'JAR'}`);
  }
  async ready(url: string) {
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
      if (!this.child?.pid || this.child.exitCode !== null || this.child.signalCode !== null) throw new Error(`Owned process failed before readiness: ${url}`);
      try { if ((await fetch(url, { signal: AbortSignal.timeout(2000) })).ok) return; } catch { /* startup */ }
      await new Promise(ok => setTimeout(ok, 300));
    }
    throw new Error(`Owned process readiness timeout: ${url}`);
  }
  async stop() {
    const child = this.child;
    if (!child || child.exitCode !== null || child.signalCode !== null) return;
    const exited = once(child, 'exit');
    const signal = (force: boolean) => {
      if (process.platform === 'win32') execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
      else process.kill(-child.pid!, force ? 'SIGKILL' : 'SIGTERM');
    };
    signal(false);
    const timer = setTimeout(() => { try { signal(true); } catch { /* exited concurrently */ } }, 10_000);
    try { await exited; console.log(`DCT teardown process=${child.pid} PASS`); }
    finally { clearTimeout(timer); this.child = undefined; }
  }
}

export class DctRuntime {
  readonly database = new DctDatabase();
  private readonly backend = new ManagedProcess();
  private readonly frontend = new ManagedProcess();
  private readonly frontendRoot = resolve(__dirname, '../../..');
  private readonly backendRoot = resolve(this.frontendRoot, '../backend');
  private jar = '';
  async start() {
    await requireFreePort(8081); await requireFreePort(4201);
    const jars = readdirSync(resolve(this.backendRoot, 'build/libs')).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'));
    if (jars.length !== 1) throw new Error('Build exactly one current bootJar before running DCT');
    this.jar = resolve(this.backendRoot, 'build/libs', jars[0]);
    await this.database.start();
    await this.startBackend();
    this.frontend.start(process.execPath, ['node_modules/@angular/cli/bin/ng.js', 'serve', '--host', 'localhost', '--port', '4201'], this.frontendRoot);
    await this.frontend.ready('http://localhost:4201');
  }
  private async startBackend() {
    // The JAR gets an allowlist rather than the parent environment. Spring also
    // accepts dotted keys such as `spring.application.json`, which cannot be
    // made safe by filtering only conventional uppercase environment names.
    const inherited: NodeJS.ProcessEnv = {};
    for (const key of ['PATH', 'Path', 'PATHEXT', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'JAVA_HOME',
      'TEMP', 'TMP', 'TMPDIR', 'HOME', 'USERPROFILE', 'LANG', 'LC_ALL', 'TZ']) {
      if (process.env[key] !== undefined) inherited[key] = process.env[key];
    }
    this.backend.start(process.env['DCT_JAVA'] ?? 'java', ['-jar', this.jar], this.backendRoot, {
      ...inherited, SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: '8081',
      BLACKSTORE_PERSISTENCE_ENABLED: 'true', BLACKSTORE_PERSISTENCE_URL: `jdbc:postgresql://127.0.0.1:${this.database.port}/dct_browser`,
      BLACKSTORE_PERSISTENCE_USERNAME: 'dct_admin', BLACKSTORE_PERSISTENCE_PASSWORD: this.database.password,
      BLACKSTORE_IDENTITY_LOOPBACK_HTTP: 'true', BLACKSTORE_IDENTITY_ALLOWED_ORIGIN: 'http://localhost:4201',
      BLACKSTORE_SALES_WORKER_ENABLED: 'false',
      SPRING_PROFILES_ACTIVE: '', BLACKSTORE_STORECORE_INTEGRATION_ENABLED: 'false', BLACKSTORE_STORECORE_INTEGRATION_MODE: 'fixture',
      BLACKSTORE_STORECORE_TRANSPORT_KILL_SWITCH: 'true', BLACKSTORE_STORECORE_CONTROL_KILL_SWITCH: 'true',
    });
    await this.backend.ready('http://127.0.0.1:8081/api/v1/auth/csrf');
    // Readiness must include the ApplicationRunner's Flyway and seed, not just the HTTP socket.
    await expect.poll(async () => {
      try { return (await this.database.query("SELECT count(*)::int AS count FROM terminals WHERE terminal_code='T-1'"))[0].count; }
      catch { return 0; }
    }).toBe(1);
  }
  async restartBackend() {
    const oldPid = this.backend.pid;
    await this.backend.stop(); await requireFreePort(8081); await this.startBackend();
    expect(this.backend.pid).toBeTruthy(); expect(this.backend.pid).not.toBe(oldPid);
  }
  async stop() {
    // Every acquired resource gets a teardown even when another teardown fails.
    const results = await Promise.allSettled([this.frontend.stop(), this.backend.stop()]);
    await this.database.stop();
    for (const result of results) if (result.status === 'rejected') throw result.reason;
    // Only check ports after this fixture acquired processes; failed preflight must not touch external owners.
    if (this.jar) { await requireFreePort(8081); await requireFreePort(4201); }
  }
}

export const test = base.extend<{ runtime: DctRuntime }>({
  runtime: async ({}, use) => {
    const runtime = new DctRuntime();
    try { await runtime.start(); await use(runtime); } finally { await runtime.stop(); }
  },
});
export { expect };

import { Page, Route, expect } from '@playwright/test';

/** Sends the real request once, then withholds its real response until explicitly lost.
 * No status, receipt or security response is fabricated. Never retry a mutation.
 */
export class DctResponseLoss {
  private release!: () => void;
  private observed!: (body: Record<string, unknown>) => void;
  private failed!: (error: unknown) => void;
  private completed!: () => void;
  private readonly gate = new Promise<void>(resolve => { this.release = resolve; });
  readonly admitted = new Promise<Record<string, unknown>>((resolve, reject) => { this.observed = resolve; this.failed = reject; });
  private readonly finished = new Promise<void>(resolve => { this.completed = resolve; });
  private started = false;
  private readonly handle = async (route: Route) => {
    this.started = true;
    try {
      const upstream = await route.fetch({ maxRetries: 0, maxRedirects: 0 });
      expect(upstream.ok()).toBe(true);
      this.observed(route.request().postDataJSON() as Record<string, unknown>);
      await this.gate;
      await route.abort('connectionreset');
    } catch (error) { this.failed(error); throw error; }
    finally { this.completed(); }
  };
  constructor(private readonly page: Page, private readonly url: string) {}
  async install(): Promise<void> { await this.page.route(this.url, this.handle, { times: 1 }); }
  async lose(): Promise<void> { this.release(); await this.finished; }
  async dispose(): Promise<void> {
    this.release();
    if (this.started) await this.finished;
    if (!this.page.isClosed()) await this.page.unroute(this.url, this.handle);
  }
}

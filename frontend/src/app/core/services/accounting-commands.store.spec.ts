import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ACCOUNTING_API_BASE } from '../api';
import { AccountingCommand, AccountingCommandKind, CommandFailure, CommandOutcome } from '../domain/accounting-command';
import { commandEnvelope } from '../infrastructure/accounting-command-test-helper';
import { AccountingCommandsStore } from './accounting-commands.store';
import { SessionStore } from './session.store';
import { authenticateTestSession } from './session-test-helper';
import { StaffRole } from '../domain/pos-types';
describe('AccountingCommandsStore', () => {
  let store: AccountingCommandsStore; let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    authenticateTestSession(TestBed.inject(SessionStore)); store = TestBed.inject(AccountingCommandsStore); http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('retains one immutable command and performs bounded GET only after response loss', () => {
    const body = { openingCash: 10 }; const command = AccountingCommand.create(AccountingCommandKind.Open, body);
    body.openingCash = 99; let outcome = CommandOutcome.Unknown;
    store.execute(command).subscribe(receipt => outcome = receipt.outcome);
    const post = http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions`);
    expect(post.request.body.openingCash).toBe(10);
    store.execute(AccountingCommand.create(AccountingCommandKind.Open, {})).subscribe();
    post.flush(null, { status: 503, statusText: 'Lost' });
    const get = http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${command.commandId}`);
    get.flush(commandEnvelope(command.commandId)); expect(outcome).toBe(CommandOutcome.Committed);
    expect(store.unresolved()).toBeNull(); http.expectNone(request => request.method === 'POST');
  });
  for (const failure of [CommandFailure.Paused, CommandFailure.NotActivated, CommandFailure.LegacyDisabled]) {
    it(`blocks further mutation after ${failure.label}`, () => {
      const command = AccountingCommand.create(AccountingCommandKind.Open, {});
      store.execute(command).subscribe();
      http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions`).flush({ code: 409, traceId: 'trace',
        data: { outcome: CommandOutcome.Unknown.wire, failure: failure.wire } }, { status: 409, statusText: 'Denied' });
      store.execute(AccountingCommand.create(AccountingCommandKind.Open, {})).subscribe();
      expect(store.blocked()).toBe(failure); http.expectNone(request => request.method === 'POST' || request.method === 'GET');
    });
  }
  it('cancels pending writes and clears evidence when identity changes', () => {
    store.execute(AccountingCommand.create(AccountingCommandKind.Open, {})).subscribe();
    const request = http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions`);
    TestBed.inject(SessionStore).changed.next();
    expect(request.cancelled).toBeTrue(); expect(store.unresolved()).toBeNull(); expect(store.receipt()).toBeNull();
  });
  it('shares concurrent receipt queries and never lets a late A delivery clear unresolved B', () => {
    const first = AccountingCommand.create(AccountingCommandKind.Open, {});
    const second = AccountingCommand.create(AccountingCommandKind.Open, {});
    store.execute(first).subscribe();
    http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions`).flush(null, { status: 503, statusText: 'Lost' });
    const query = http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${first.commandId}`);
    store.consult().subscribe(() => store.execute(second).subscribe());
    store.consult().subscribe();
    http.expectNone(request => request.method === 'GET');
    query.flush(commandEnvelope(first.commandId));
    expect(store.unresolved()).toBe(second);
    expect(store.receipt()?.commandId).toBe(first.commandId);
    store.execute(AccountingCommand.create(AccountingCommandKind.Open, {})).subscribe();
    const secondPost = http.expectOne(`${ACCOUNTING_API_BASE}/cash-sessions`);
    http.expectNone(request => request.method === 'POST');
    secondPost.flush(null, { status: 503, statusText: 'Lost' });
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${second.commandId}`).flush(null);
    expect(store.unresolved()).toBe(second);
    store.consult().subscribe();
    http.expectOne(`${ACCOUNTING_API_BASE}/accounting/commands/${second.commandId}`).flush(commandEnvelope(second.commandId));
    expect(store.unresolved()).toBeNull();
  });
  it('does not send a mutation for an auditor', () => {
    authenticateTestSession(TestBed.inject(SessionStore), StaffRole.Auditor);
    store.execute(AccountingCommand.create(AccountingCommandKind.Capture, {})).subscribe();
    http.expectNone(request => request.method === 'POST');
  });
});

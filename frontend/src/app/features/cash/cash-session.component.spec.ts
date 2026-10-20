import { signal } from '@angular/core';
import { of } from 'rxjs';
import { AccountingCommandsStore } from '../../core/services/accounting-commands.store';
import { AccountingCommandKind } from '../../core/domain/accounting-command';
import { PosWireMapper } from '../../core/infrastructure/pos-wire-mapper';
const commands={ runtime:{notice:signal('Comprobado')},unresolved:signal(null),blocked:signal(CommandFailure.None),receipt:signal(null),execute:jasmine.createSpy().and.callFake(()=>of(PosWireMapper.commandReceipt(null,''))),consult:jasmine.createSpy().and.callFake(()=>of(PosWireMapper.commandReceipt(null,''))) };
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AccountingCoverage, CommandFailure, CommandOutcome, ExpenseOperation } from '../../core/domain/accounting-command';
import { ReconciliationOutcome } from '../../core/domain/accounting-report';
import { ACCOUNTING_API_BASE, API_BASE } from '../../core/api';
import { CashSessionComponent } from './cash-session.component';
import { SessionStore } from '../../core/services/session.store';
import { authenticateTestSession } from '../../core/services/session-test-helper';
import { StaffRole, CashSessionStatus, CashMutationOutcome, PaymentMethod } from '../../core/domain/pos-types';
import { CounterContextService } from '../../core/services/counter-context.service';

describe('CashSessionComponent', () => {
  beforeEach(() => { commands.blocked.set(CommandFailure.None); commands.execute.calls.reset(); });
  it('fails closed when the backend returns an unknown session status', async () => {
    await TestBed.configureTestingModule({
      imports: [CashSessionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), {provide:AccountingCommandsStore,useValue:commands}],
    }).compileComponents();

    authenticateTestSession(TestBed.inject(SessionStore));
    const fixture = TestBed.createComponent(CashSessionComponent);
    const http = TestBed.inject(HttpTestingController);
    const unknownSession = {
      id: 5,
      terminalId: 10,
      cashierId: 7,
      status: 'FUTURE_CASH_STATE',
      openingCash: 100,
    };

    http.match(`${API_BASE}/workspace`).forEach((request) => request.flush(envelope({
      terminalId: 10,
      cashierId: 7,
      persistence: 'memory',
    })));
    http.match(`${API_BASE}/cash-sessions`).forEach((request) => request.flush(envelope([unknownSession])));
    http.expectOne(`${API_BASE}/catalog`).flush(envelope({
      version: 'catalog-v1',
      stale: false,
      importedAt: null,
      validUntil: null,
      items: [],
    }));
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    const openButton = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(text).toContain('estado desconocido');
    expect(text).not.toContain('FUTURE_CASH_STATE');
    expect(openButton.disabled).toBeTrue();

    fixture.componentInstance.requestClose();
    fixture.componentInstance.addExpense();
    fixture.componentInstance.open();
    http.expectNone((request) => request.method === 'POST');
    http.verify();
  });

  it('does not let a historical reconciliation state block a new session', async () => {
    await TestBed.configureTestingModule({
      imports: [CashSessionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), {provide:AccountingCommandsStore,useValue:commands}],
    }).compileComponents();

    authenticateTestSession(TestBed.inject(SessionStore));
    const fixture = TestBed.createComponent(CashSessionComponent);
    const http = TestBed.inject(HttpTestingController);
    flushInitialRequests(http, 'RECONCILIATION_REQUIRED');
    fixture.detectChanges();

    const openButton = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(fixture.componentInstance.session()).toBeNull();
    expect(openButton.disabled).toBeFalse();
    http.verify();
  });
});

describe('CashSessionComponent journal command port',()=>{
  let component:CashSessionComponent;let http:HttpTestingController;
  beforeEach(()=>{
    commands.blocked.set(CommandFailure.None);commands.execute.calls.reset();
    TestBed.configureTestingModule({imports:[CashSessionComponent],providers:[provideHttpClient(),provideHttpClientTesting(),{provide:AccountingCommandsStore,useValue:commands}]});
    authenticateTestSession(TestBed.inject(SessionStore));component=TestBed.createComponent(CashSessionComponent).componentInstance;http=TestBed.inject(HttpTestingController);flushInitialRequests(http,CashSessionStatus.Open.wire);
  });
  afterEach(()=>http.verify());
  it('hands close and expense intentions to the shared durable command port',()=>{
    component.declared=0;component.closeReason='cierre controlado';component.requestClose();
    expect(commands.execute).not.toHaveBeenCalled();expect(component.closePhase().isReviewing).toBeTrue();
    component.confirmClose();expect(commands.execute.calls.mostRecent().args[0].kind).toBe(AccountingCommandKind.Close);
  });
  it('does not open close confirmation without explicit amount and reason',()=>{
    component.requestClose();expect(component.closePhase().isReviewing).toBeFalse();expect(commands.execute).not.toHaveBeenCalled();
  });
  it('rejects subcent and overflow money before creating any durable command',()=>{
    component.closeReason='valid reason';
    for(const invalid of [0.001,1000000000000]){
      component.declared=invalid;component.requestClose();
      expect(component.closePhase().isReviewing).toBeFalse();expect(commands.execute).not.toHaveBeenCalled();
      component.openingCash=invalid;expect(component.canOpen()).toBeFalse();component.open();
      component.expenseAmount=invalid;component.addExpense();expect(commands.execute).not.toHaveBeenCalled();
    }
  });
  it('invalidates the close review when the selected cash session changes',()=>{
    component.declared=0;component.closeReason='cierre controlado';component.requestClose();
    expect(component.closePhase().isReviewing).toBeTrue();
    component.terminalId=999;component.confirmClose();
    expect(component.closePhase().isReviewing).toBeFalse();
    expect(component.notice()).toContain('caja seleccionada cambió');
    expect(commands.execute).not.toHaveBeenCalled();
  });
  it('blocks action controls on runtime pause, uncertainty and quarantine',()=>{
    commands.blocked.set(CommandFailure.Paused);expect(component.canMutate()).toBeFalse();component.requestClose();component.addExpense();expect(commands.execute).not.toHaveBeenCalled();http.expectNone(r=>r.method==='POST');
  });
});
function flushInitialRequests(http: HttpTestingController, status: string): void {
  http.match(`${API_BASE}/workspace`).forEach((request) => request.flush(envelope({
    terminalId: 10,
    cashierId: 7,
    persistence: 'memory',
  })));
  http.match(`${API_BASE}/cash-sessions`).forEach((request) => request.flush(envelope([{
    id: 5,
    terminalId: 10,
    cashierId: 7,
    status,
    openingCash: 100,
  }])));
  http.expectOne(`${API_BASE}/catalog`).flush(envelope({
    version: 'catalog-v1',
    stale: false,
    importedAt: null,
    validUntil: null,
    items: [],
  }));
}

function envelope<T>(data: T) {
  return { code: 200, traceId: 'test', data, message: null, errorCode: null, retryable: null };
}

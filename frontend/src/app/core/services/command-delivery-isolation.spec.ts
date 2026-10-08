import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {firstValueFrom,Subject} from 'rxjs';
import {AccountingCommandsStore} from './accounting-commands.store';
import {SaleCommandsStore} from './sale-commands.store';
import {CommandRuntimeStore} from './command-runtime.store';
import {SessionStore} from './session.store';
import {AccountingCommand,AccountingCommandKind} from '../domain/accounting-command';
import {SaleCommand,SaleCommandKind,SaleAdmissionOutcome} from '../domain/sale-command';
import {PosWireMapper} from '../infrastructure/pos-wire-mapper';
import {commandEnvelope} from '../infrastructure/accounting-command-test-helper';
describe('per-subscriber command delivery identity',()=>{
  let runtime:any;let session:any;
  beforeEach(()=>{
    runtime={pending:signal(null),canWrite:signal(true),execute:jasmine.createSpy(),consult:jasmine.createSpy()};
    session={staff:signal({id:7}),generation:signal(1),changed:new Subject<void>()};
    TestBed.configureTestingModule({providers:[{provide:CommandRuntimeStore,useValue:runtime},{provide:SessionStore,useValue:session}]});
  });
  const sale=()=>SaleCommand.create(SaleCommandKind.Commit,{clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'},2,{});
  for(const family of ['Accounting','Sale'] as const){
    for(const operation of ['execute','consult'] as const){
      it(family+' '+operation+' never replays a completed private receipt to actor 8',async()=>{
        const store=family==='Accounting'?TestBed.inject(AccountingCommandsStore):TestBed.inject(SaleCommandsStore);
        const command=family==='Accounting'?AccountingCommand.create(AccountingCommandKind.Open,{}):sale();
        const result=family==='Accounting'?PosWireMapper.commandReceipt(commandEnvelope(command.commandId),command.commandId):{outcome:SaleAdmissionOutcome.Accepted,receipt:{actorId:7,commandId:command.commandId}};
        runtime[operation].and.returnValue(Promise.resolve(result));
        const response=operation==='execute'?(store as any).execute(command):store.consult();
        expect(await firstValueFrom(response)).toBe(result);
        session.changed.next();session.staff.set({id:8});session.generation.set(2);
        const delivered:unknown[]=[];response.subscribe((value:unknown)=>delivered.push(value));
        expect(delivered).toEqual([]);expect(runtime[operation].calls.count()).toBe(1);
      });
      it(family+' '+operation+' does not start a stale deferred request after changed',()=>{
        const store=family==='Accounting'?TestBed.inject(AccountingCommandsStore):TestBed.inject(SaleCommandsStore);
        const response=operation==='execute'?(store as any).execute(family==='Accounting'?AccountingCommand.create(AccountingCommandKind.Open,{}):sale()):store.consult();
        session.changed.next();session.staff.set({id:8});session.generation.set(2);response.subscribe();
        expect(runtime.execute).not.toHaveBeenCalled();expect(runtime.consult).not.toHaveBeenCalled();
      });
    }
  }
});

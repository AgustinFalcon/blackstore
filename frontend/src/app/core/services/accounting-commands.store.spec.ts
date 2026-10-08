import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject,firstValueFrom } from 'rxjs';
import { AccountingCommand,AccountingCommandKind,CommandOutcome } from '../domain/accounting-command';
import { JournalFamily,JournalPhase } from '../domain/command-journal';
import { CommandRuntimeStore } from './command-runtime.store';
import { SessionStore } from './session.store';
import { AccountingCommandsStore } from './accounting-commands.store';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { commandEnvelope } from '../infrastructure/accounting-command-test-helper';
describe('AccountingCommandsStore typed journal facade',()=>{
  let runtime:any;let session:any;let store:AccountingCommandsStore;
  beforeEach(()=>{
    runtime={pending:signal(null),canWrite:signal(true),execute:jasmine.createSpy(),consult:jasmine.createSpy()};
    session={changed:new Subject<void>(),staff:signal({id:7}),generation:signal(1)};
    TestBed.configureTestingModule({providers:[{provide:CommandRuntimeStore,useValue:runtime},{provide:SessionStore,useValue:session}]});store=TestBed.inject(AccountingCommandsStore);
  });
  for(const kind of [AccountingCommandKind.Open,AccountingCommandKind.Close,AccountingCommandKind.Expense,AccountingCommandKind.Capture,AccountingCommandKind.Reverse]){
    it('delegates '+kind.wire+' to the durable coordinator once for shared subscribers',async()=>{
      const command=AccountingCommand.create(kind,{amount:'10'});runtime.execute.and.returnValue(Promise.resolve(PosWireMapper.commandReceipt(commandEnvelope(command.commandId),command.commandId)));
      const response=store.execute(command);await Promise.all([firstValueFrom(response),firstValueFrom(response)]);
      expect(runtime.execute.calls.count()).toBe(1);expect(runtime.execute).toHaveBeenCalledWith(command);
    });
  }
  it('masks sale family rather than querying accounting endpoint for it',()=>{
    runtime.pending.set({command:{},family:JournalFamily.Sale,phase:JournalPhase.Prepared});expect(store.unresolved()).toBeNull();
  });
  it('consult is read-only delegation and session change clears visible receipt',async()=>{
    const command=AccountingCommand.create(AccountingCommandKind.Open,{});runtime.consult.and.returnValue(Promise.resolve(PosWireMapper.commandReceipt(commandEnvelope(command.commandId),command.commandId)));
    expect((await firstValueFrom(store.consult())).outcome).toBe(CommandOutcome.Committed);expect(runtime.execute).not.toHaveBeenCalled();
    session.changed.next();expect(store.receipt()).toBeNull();
  });
});

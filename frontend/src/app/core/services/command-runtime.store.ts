import { DestroyRef,Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AccountingCommand, AccountingCommandKind, CommandOutcome, CommandReceipt } from '../domain/accounting-command';
import { AccountingLifecycleState } from '../domain/accounting-lifecycle';
import { JournalEntry, JournalFamily, JournalPhase, JournalScope, sameScope } from '../domain/command-journal';
import { PosContextState, PosExecutionContext } from '../domain/pos-execution-context';
import { SaleAdmission, SaleAdmissionOutcome, SaleCommand,SaleCommandKind,SaleAdmissionVerification } from '../domain/sale-command';
import { IndexedDbCommandJournal } from '../infrastructure/indexed-db-command-journal';
import { CommandHttp } from '../infrastructure/command-http';
import { PosWireMapper } from '../infrastructure/pos-wire-mapper';
import { SessionStore } from './session.store';
import { SaleAdmissionFingerprint } from '../infrastructure/sale-admission-fingerprint';
import {FOREGROUND_REFRESH} from '../infrastructure/foreground-refresh';

/** Shared coordinator: evidence is durable; a session generation only owns visible observations. */
@Injectable({ providedIn: 'root' })
export class CommandRuntimeStore {
  private readonly session = inject(SessionStore);
  private readonly journal = inject(IndexedDbCommandJournal);
  private readonly api = inject(CommandHttp);
  private readonly tabId = crypto.randomUUID();
  private epoch = 0;
  private locked = false;
  private recovery: { key: string; promise: Promise<CommandReceipt | SaleAdmission> } | null = null;
  readonly context = signal<PosExecutionContext | null>(null);
  readonly contextState = signal(PosContextState.Unknown);
  readonly lifecycle = signal(AccountingLifecycleState.Unknown);
  readonly hydrated = signal(false);
  readonly notice = signal('Comprobando contexto, contabilidad y evidencia local…');
  readonly pending = signal<JournalEntry | null>(null);
  readonly neutralBlock = signal(true);
  readonly canWrite = computed(() => this.hydrated() && !this.neutralBlock() && !this.pending() && !!this.context() && this.lifecycle().admitsWrites);
  refresh(): void {
    const staff = this.session.staff();
    if (staff && this.session.state().isAuthenticated && !this.locked) void this.hydrate(this.session.generation(),staff.id);
  }
  constructor() {
    const destroy=inject(DestroyRef);
    const sessionChange=this.session.changed.subscribe(()=>this.invalidate('Comprobando sesión y evidencia…'));
    const unsubscribe=inject(FOREGROUND_REFRESH).subscribe(()=>{
      this.invalidate('Contexto desactualizado. Comprobando contabilidad y evidencia…');
      const staff=this.session.staff();
      if(staff && this.session.state().isAuthenticated)void this.hydrate(this.session.generation(),staff.id);
    });
    destroy.onDestroy(()=>{++this.epoch;sessionChange.unsubscribe();unsubscribe();});
    effect(() => {
      const staff = this.session.staff(); const generation = this.session.generation();
      if (staff && this.session.state().isAuthenticated) void this.hydrate(generation, staff.id);
    });
  }
  private invalidate(notice:string):void{
    ++this.epoch;this.context.set(null);this.contextState.set(PosContextState.Unknown);this.lifecycle.set(AccountingLifecycleState.Unknown);
    this.pending.set(null);this.hydrated.set(false);this.neutralBlock.set(true);this.notice.set(notice);
  }
  private current(epoch: number, generation: number, actor: number): boolean {
    return epoch === this.epoch && generation === this.session.generation() && actor === this.session.staff()?.id && this.session.state().isAuthenticated;
  }
  private async hydrate(generation: number, actor: number): Promise<void> {
    const epoch = ++this.epoch; this.hydrated.set(false); this.neutralBlock.set(true); this.pending.set(null);
    try {
      const [contextRaw,lifecycleRaw,snapshot] = await Promise.all([firstValueFrom(this.api.context()),firstValueFrom(this.api.lifecycle()),this.journal.list()]);
      if (!this.current(epoch,generation,actor)) return;
      const observation = PosWireMapper.posContext(contextRaw); this.contextState.set(observation.state); this.context.set(observation.context); this.lifecycle.set(PosWireMapper.lifecycle(lifecycleRaw));
      const scope = observation.context ? { origin: globalThis.location.origin, ...observation.context } : null;
      const unresolved = snapshot.entries.filter(entry => entry.phase !== JournalPhase.Resolved);
      if (!scope || snapshot.quarantined || unresolved.some(entry => !sameScope(entry.scope,scope) || entry.actorId !== actor) || unresolved.length > 1) {
        this.notice.set(snapshot.quarantined ? 'Evidencia local en cuarentena. Requiere conciliación operativa.' : 'Contexto o evidencia pendiente no disponible para esta sesión. Requiere conciliación.'); return;
      }
      this.neutralBlock.set(false); this.hydrated.set(true); this.pending.set(unresolved[0] ?? null);
      this.notice.set(this.lifecycle().label);
      if (unresolved[0]) await this.recover(unresolved[0],epoch,generation,actor);
    } catch { if (this.current(epoch,generation,actor)) { this.neutralBlock.set(true); this.notice.set('No se pudo confirmar almacenamiento local. Operaciones bloqueadas.'); } }
  }
  async execute(command: AccountingCommand | SaleCommand): Promise<CommandReceipt | SaleAdmission> {
    const unknown = this.unknown(command); const actor = this.session.staff()?.id; const generation = this.session.generation(); const epoch = this.epoch;
    if (!actor || !this.canWrite() || this.locked || !this.session.can(command.kind.permission)) return unknown;
    this.locked = true;
    try {
      const [rawContext,rawLifecycle] = await Promise.all([firstValueFrom(this.api.context()),firstValueFrom(this.api.lifecycle())]);
      if (!this.current(epoch,generation,actor)) return unknown;
      const observed = PosWireMapper.posContext(rawContext); const lifecycle = PosWireMapper.lifecycle(rawLifecycle);
      if (!observed.context || JSON.stringify(observed.context) !== JSON.stringify(this.context()) || !lifecycle.admitsWrites) {
        this.context.set(null); this.lifecycle.set(lifecycle); this.neutralBlock.set(true); this.notice.set('Contexto cambiado o contabilidad no habilitada. Operaciones bloqueadas.'); return unknown;
      }
      const scope = Object.freeze({ origin: globalThis.location.origin, ...observed.context });
      if (command instanceof SaleCommand && (command.identity.clientInstanceId !== scope.clientInstanceId || command.identity.deviceId !== scope.deviceId)) return unknown;
      const body = command.body;
      const cashSessionId = await firstValueFrom(this.api.references(command,scope.terminalId));
      if (cashSessionId === false || !this.current(epoch,generation,actor)) return unknown;
      if (body['terminalId'] != null && body['terminalId'] !== scope.terminalId) return unknown;
      const fingerprint=command instanceof SaleCommand ? {expectedPayloadHash:await SaleAdmissionFingerprint.hash(command,actor),fingerprintVersion:SaleAdmissionFingerprint.version} : {};
      if(!this.current(epoch,generation,actor))return unknown;
      const entry: JournalEntry = Object.freeze({ version: 1, scope, family: command instanceof SaleCommand ? JournalFamily.Sale : JournalFamily.Accounting,
        command, actorId: actor, cashSessionId, phase: JournalPhase.Prepared, tabId: this.tabId,...fingerprint });
      await this.journal.prepare(entry); // oncomplete, not request success, is the send barrier.
      if (!this.current(epoch,generation,actor)) return unknown;
      this.pending.set(entry); this.notice.set('Intención conservada. Esperando recibo…');
      await this.journal.transition(entry,JournalPhase.AwaitingReceipt);
      if (!this.current(epoch,generation,actor) || !this.session.can(command.kind.permission)) return unknown;
      return await this.record(entry,await firstValueFrom(this.api.post(command)),epoch,generation,actor);
    } catch { if (this.current(epoch,generation,actor)) { this.neutralBlock.set(true); this.notice.set('Resultado incierto o storage no confirmado. Consultá; no reenvíes.'); } return unknown; }
    finally { this.locked = false; }
  }
  async consult(): Promise<CommandReceipt | SaleAdmission | null> {
    const entry = this.pending(); const actor = this.session.staff()?.id;
    return entry && actor && entry.phase!==JournalPhase.Quarantined ? this.recover(entry,this.epoch,this.session.generation(),actor) : null;
  }
  private recover(entry: JournalEntry, epoch: number,generation: number,actor: number): Promise<CommandReceipt | SaleAdmission> {
    if (!this.current(epoch,generation,actor) || entry.actorId !== actor || !this.session.can(entry.command.kind.permission)) return Promise.resolve(this.unknown(entry.command));
    const key = JSON.stringify([epoch,generation,actor,entry.family.wire,entry.command.commandId]);
    if (this.recovery?.key === key) return this.recovery.promise;
    const promise = firstValueFrom(this.api.receipt(entry.command)).then(raw => this.record(entry,raw,epoch,generation,actor))
      .catch(() => this.unknown(entry.command)).finally(() => { if (this.recovery?.key === key) this.recovery = null; });
    this.recovery = { key,promise }; return promise;
  }
  private async record(entry: JournalEntry,raw: unknown,epoch: number,generation: number,actor: number): Promise<CommandReceipt | SaleAdmission> {
    const command = entry.command; const unknown = this.unknown(command);
    if (!this.current(epoch,generation,actor) || this.pending()?.command.commandId !== command.commandId || !this.session.can(command.kind.permission)) return unknown;
    if(command instanceof SaleCommand && (entry.fingerprintVersion!==SaleAdmissionFingerprint.version || !entry.expectedPayloadHash ||
      await SaleAdmissionFingerprint.hash(command,entry.actorId)!==entry.expectedPayloadHash)){
      this.neutralBlock.set(true);this.notice.set('Fingerprint de evidencia local incompatible. Cuarentena; requiere conciliación.');return unknown;
    }
    if(!this.current(epoch,generation,actor))return unknown;
    const result = command instanceof SaleCommand ? PosWireMapper.saleAdmission(raw,command,actor,entry.expectedPayloadHash??null) : PosWireMapper.commandReceipt(raw,command.commandId);
    if(command instanceof SaleCommand && (result as SaleAdmission).verification===SaleAdmissionVerification.PayloadMismatch){
      await this.journal.transition(entry,JournalPhase.Quarantined);
      if(this.current(epoch,generation,actor)){this.pending.set({...entry,phase:JournalPhase.Quarantined});this.neutralBlock.set(true);this.notice.set('Fingerprint de recibo incompatible con intención congelada. Cuarentena; requiere conciliación.');}
      return unknown;
    }
    const valid = command instanceof SaleCommand ? (result as SaleAdmission).outcome === SaleAdmissionOutcome.Accepted : (result as CommandReceipt).outcome === CommandOutcome.Committed && command.accepts(result as CommandReceipt);
    if (!valid) { this.notice.set(`${result.outcome.label}. Sin reenvío automático.`); return unknown; }
    await this.journal.transition(entry,JournalPhase.ReceiptVerifiedAwaitingRefresh);
    if (!this.current(epoch,generation,actor)) return unknown;
    this.pending.set({ ...entry, phase: JournalPhase.ReceiptVerifiedAwaitingRefresh }); this.notice.set('Recibo comprobado. Falta refresh autoritativo.');
    if(command instanceof SaleCommand && command.kind===SaleCommandKind.Reserve)
      this.notice.set('Reserva admitida. Esperando comprobación autoritativa; sólo consultas, sin cobro.');
    const refreshed = await firstValueFrom(this.api.refresh(command, command instanceof AccountingCommand ? result as CommandReceipt : undefined,
      ()=>this.current(epoch,generation,actor) && this.pending()?.command.commandId===command.commandId && this.pending()?.family===entry.family &&
        !!this.context() && sameScope(entry.scope,{origin:globalThis.location.origin,...this.context()!}) && this.session.can(command.kind.permission),actor));
    if (!this.current(epoch,generation,actor) || !refreshed) {
      if(this.current(epoch,generation,actor) && command instanceof AccountingCommand && command.kind===AccountingCommandKind.Expense)
        this.notice.set('Egreso admitido; proyección no comprobada. Intención conservada: consultá sin reenviar.');
      if(this.current(epoch,generation,actor) && command instanceof SaleCommand && command.kind===SaleCommandKind.Reserve)
        this.notice.set('Reserva aún no comprobada. Intención conservada; consultá sin reenviar ni cobrar.');
      return unknown;
    }
    if(command instanceof AccountingCommand && command.kind===AccountingCommandKind.Expense){
      const [contextRaw,lifecycleRaw]=await Promise.all([firstValueFrom(this.api.context()),firstValueFrom(this.api.lifecycle())]);
      if(!this.current(epoch,generation,actor) || !this.session.can(command.kind.permission))return unknown;
      const context=PosWireMapper.posContext(contextRaw),lifecycle=PosWireMapper.lifecycle(lifecycleRaw);
      if(!context.context || !sameScope(entry.scope,{origin:globalThis.location.origin,...context.context}) || lifecycle===AccountingLifecycleState.Unknown){
        this.neutralBlock.set(true);this.notice.set('Proyección comprobada; contexto o contabilidad no confirmados. Consultá sin reenviar.');return unknown;
      }
      this.context.set(context.context);this.contextState.set(context.state);this.lifecycle.set(lifecycle);
    }
    await this.journal.transition(entry,JournalPhase.Resolved);
    if (this.current(epoch,generation,actor) && this.pending()?.command.commandId === command.commandId) { this.pending.set(null); this.neutralBlock.set(false); this.notice.set(this.lifecycle().label); }
    return result;
  }
  private unknown(command: AccountingCommand | SaleCommand): CommandReceipt | SaleAdmission {
    return command instanceof SaleCommand ? { outcome: SaleAdmissionOutcome.Unknown, receipt: null } : PosWireMapper.commandReceipt(null,command.commandId);
  }
}

import { JournalPhase } from './command-journal';
describe('closed monotonic journal phase policy',()=>{
  const phases=[JournalPhase.Prepared,JournalPhase.AwaitingReceipt,JournalPhase.ReceiptVerifiedAwaitingRefresh,JournalPhase.Resolved,JournalPhase.Quarantined];
  it('keeps terminal and duplicate evidence idempotently',()=>{
    for(const phase of phases){expect(JournalPhase.Resolved.nextPhase(phase)).toBe(JournalPhase.Resolved);expect(phase.nextPhase(phase)).toBe(phase);}
  });
  it('advances receipt recovery while ignoring already completed earlier steps',()=>{
    expect(JournalPhase.Prepared.nextPhase(JournalPhase.AwaitingReceipt)).toBe(JournalPhase.AwaitingReceipt);
    expect(JournalPhase.Prepared.nextPhase(JournalPhase.ReceiptVerifiedAwaitingRefresh)).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);
    expect(JournalPhase.AwaitingReceipt.nextPhase(JournalPhase.ReceiptVerifiedAwaitingRefresh)).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);
    expect(JournalPhase.ReceiptVerifiedAwaitingRefresh.nextPhase(JournalPhase.Resolved)).toBe(JournalPhase.Resolved);
    expect(JournalPhase.AwaitingReceipt.nextPhase(JournalPhase.Prepared)).toBe(JournalPhase.AwaitingReceipt);
    expect(JournalPhase.ReceiptVerifiedAwaitingRefresh.nextPhase(JournalPhase.AwaitingReceipt)).toBe(JournalPhase.ReceiptVerifiedAwaitingRefresh);
  });
  it('rejects verification skips unknown phases and quarantine escape',()=>{
    expect(JournalPhase.Prepared.nextPhase(JournalPhase.Resolved)).toBeNull();expect(JournalPhase.AwaitingReceipt.nextPhase(JournalPhase.Resolved)).toBeNull();
    for(const phase of phases){expect(phase.nextPhase(JournalPhase.Unknown)).toBeNull();expect(JournalPhase.Unknown.nextPhase(phase)).toBeNull();
      if(phase!==JournalPhase.Quarantined)expect(JournalPhase.Quarantined.nextPhase(phase)).toBeNull();}
    expect(JournalPhase.AwaitingReceipt.nextPhase(JournalPhase.Quarantined)).toBe(JournalPhase.Quarantined);
  });
});

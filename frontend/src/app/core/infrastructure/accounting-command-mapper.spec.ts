import { AccountingCommand, AccountingCommandKind, AccountingCoverage, CommandFailure, CommandOutcome, ExpenseOperation } from '../domain/accounting-command';
import { ReconciliationOutcome } from '../domain/accounting-report';
import { PosWireMapper } from './pos-wire-mapper';
import { closeFixture, commandEnvelope } from './accounting-command-test-helper';
describe('Accounting command edge', () => {
  const id = '11111111-1111-4111-8111-111111111111';
  it('uses the nullable capture DTO default and normalizes optional reason before freezing intent', () => {
    for (const body of [{}, { reason: null }, { reason: '' }, { reason: ' \t\n ' }]) {
      expect(AccountingCommand.create(AccountingCommandKind.Capture, body).body['reason']).toBeNull();
    }
    const command = AccountingCommand.create(AccountingCommandKind.Capture, { reason: '  caja ajena  ' });
    expect(command.body['reason']).toBe('caja ajena');
    expect(Object.isFrozen(command.body)).toBeTrue();
  });
  it('translates all closed outcomes and failures, including Unknown', () => {
    for (const outcome of [CommandOutcome.Committed, CommandOutcome.NotFound, CommandOutcome.Unavailable]) {
      expect(CommandOutcome.fromWire(outcome.wire)).toBe(outcome);
    }
    for (const failure of [CommandFailure.None, CommandFailure.NotVisible, CommandFailure.Forbidden, CommandFailure.Validation,
      CommandFailure.Closed, CommandFailure.TransitionConflict, CommandFailure.CashConflict, CommandFailure.NonTerminalSale,
      CommandFailure.PayloadMismatch, CommandFailure.LegacyDisabled, CommandFailure.NotActivated, CommandFailure.Paused, CommandFailure.Unavailable]) {
      expect(CommandFailure.fromWire(failure.wire)).toBe(failure);
    }
    expect(CommandOutcome.fromWire('future')).toBe(CommandOutcome.Unknown);
    expect(CommandFailure.fromWire('future')).toBe(CommandFailure.Unknown);
    expect(AccountingCoverage.fromWire('future')).toBe(AccountingCoverage.Unknown);
    expect(ExpenseOperation.fromWire('future')).toBe(ExpenseOperation.Unknown);
  });
  it('requires correlated command identity, receipt evidence and aggregate IDs', () => {
    expect(PosWireMapper.commandReceipt(commandEnvelope(id), id).outcome).toBe(CommandOutcome.Committed);
    for (const overrides of [{ commandId: 'other' }, { cashSessionId: 0 }, { paymentId: -1 }, { ledgerEventIds: [1, 1] },
      { committedAt: 'invalid' }, { failure: CommandFailure.Paused.wire }]) {
      expect(PosWireMapper.commandReceipt(commandEnvelope(id, overrides), id).outcome).toBe(CommandOutcome.Unknown);
    }
  });
  it('validates exact money and reconciliation sign and rejects unknown raw labels', () => {
    expect(PosWireMapper.commandReceipt(commandEnvelope(id, { closeSnapshot: closeFixture() }), id).closeSnapshot?.difference?.decimal).toBe('-10.00');
    for (const overrides of [{ difference: '-9' }, { difference: '-10.001' }, { outcome: ReconciliationOutcome.Overage.wire },
      { outcome: 'private-future' }, { coverage: 'private-future' }]) {
      const receipt = PosWireMapper.commandReceipt(commandEnvelope(id, { closeSnapshot: { ...closeFixture(), ...overrides } }), id);
      expect(receipt.outcome).toBe(CommandOutcome.Unknown); expect(receipt.closeSnapshot).toBeNull();
    }
    const legacy = { ...closeFixture(), expectedCash: null, difference: null, outcome: ReconciliationOutcome.Unavailable.wire, coverage: AccountingCoverage.LegacyIncomplete.wire };
    const mapped = PosWireMapper.commandReceipt(commandEnvelope(id, { closeSnapshot: legacy }), id).closeSnapshot;
    expect(mapped?.expected).toBeNull(); expect(mapped?.difference).toBeNull();
  });
});

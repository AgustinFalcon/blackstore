import { OperationId, SaleOperationSelection, SaleSelectionPhase } from './sale-operation-selection';

describe('sale operation selection closed domain', () => {
  const operation = '33333333-3333-3333-3333-333333333333';

  it('translates exactly one UUID and preserves its route identity', () => {
    const selection = SaleOperationSelection.fromWire([operation]);
    const equivalent = SaleOperationSelection.fromWire([operation.toUpperCase()]);
    expect(selection.isSelected).toBeTrue();
    expect(selection.operationId?.wire).toBe(operation);
    expect(selection.operationId?.equals(OperationId.fromWire(operation))).toBeTrue();
    expect(equivalent.operationId?.wire).toBe(operation);
    expect(selection.equals(equivalent)).toBeTrue();
    expect(selection.equals(SaleOperationSelection.None)).toBeFalse();
  });

  it('fails closed for missing, malformed or duplicate query values', () => {
    expect(SaleOperationSelection.fromWire([])).toBe(SaleOperationSelection.None);
    for (const raw of [['not-an-operation'], [operation, operation], [null], [42]]) {
      expect(SaleOperationSelection.fromWire(raw)).toBe(SaleOperationSelection.Unknown);
    }
    expect(OperationId.fromWire(' ')).toBeNull();
  });

  it('round-trips every known phase and neutralizes unknown wire values', () => {
    for (const phase of SaleSelectionPhase.values) expect(SaleSelectionPhase.fromWire(phase.wire)).toBe(phase);
    expect(SaleSelectionPhase.fromWire('FUTURE_PHASE')).toBe(SaleSelectionPhase.Unknown);
    expect(SaleSelectionPhase.Loading.showsPanel).toBeTrue();
    expect(SaleSelectionPhase.Unavailable.canRetry).toBeTrue();
    expect(SaleSelectionPhase.Unknown.canRetry).toBeFalse();
  });
});

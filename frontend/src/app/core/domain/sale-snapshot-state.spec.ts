import { SaleRouteId, SaleSnapshotState } from './sale-snapshot-state';

describe('sale snapshot route domain', () => {
  it('preserves the opaque case-sensitive sale id contract', () => {
    expect(SaleRouteId.fromWire('Sale-Aa-01')?.wire).toBe('Sale-Aa-01');
    expect(SaleRouteId.fromWire('Sale-Aa-01')?.matches('Sale-Aa-01')).toBeTrue();
    expect(SaleRouteId.fromWire('Sale-Aa-01')?.matches('sale-aa-01')).toBeFalse();
    expect(SaleRouteId.fromWire('x'.repeat(80))?.wire).toBe('x'.repeat(80));
    expect(SaleRouteId.fromWire('../operation')?.wire).toBe('../operation');
    expect(SaleRouteId.fromWire('x'.repeat(81))).toBeNull();
    expect(SaleRouteId.fromWire(' sale')).toBeNull();
    expect(SaleRouteId.fromWire('sale\u0000')).toBeNull();
    expect(SaleRouteId.fromWire('\ud800')).toBeNull();
    expect(SaleRouteId.fromWire('')).toBeNull();
  });

  it('round-trips dot segments through an explicit path representation', () => {
    for (const wire of ['.', '..', 'Sale/Aa-01', 'Sale#Aa-01', 'Sale?Aa-01']) {
      const value = SaleRouteId.fromWire(wire)!;
      expect(SaleRouteId.fromPathSegment(value.pathSegment)?.wire).toBe(wire);
    }
    expect(SaleRouteId.fromPathSegment('..')).toBeNull();
    expect(SaleRouteId.fromPathSegment('id:')).toBeNull();
  });

  it('keeps every lookup result closed and fail-closed', () => {
    expect(SaleSnapshotState.fromWire('FOUND')).toBe(SaleSnapshotState.Found);
    expect(SaleSnapshotState.fromWire('future')).toBe(SaleSnapshotState.Unknown);
    expect(SaleSnapshotState.Ambiguous.showsDetail).toBeFalse();
    expect(SaleSnapshotState.Unknown.showsDetail).toBeFalse();
  });
});

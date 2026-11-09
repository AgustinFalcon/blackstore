import { PersistenceReadiness, SurfaceDataSource } from './data-provenance';

describe('closed data provenance', () => {
  it('translates wire values once and fails closed', () => {
    expect(SurfaceDataSource.fromWire('REAL')).toBe(SurfaceDataSource.Real);
    expect(SurfaceDataSource.fromWire('future')).toBe(SurfaceDataSource.Unknown);
    expect(PersistenceReadiness.fromWire('postgresql')).toBe(PersistenceReadiness.Durable);
    expect(PersistenceReadiness.fromWire('memory')).toBe(PersistenceReadiness.MemoryResettable);
    expect(PersistenceReadiness.fromWire('future')).toBe(PersistenceReadiness.Unknown);
  });

  it('aggregates required datasets with deterministic precedence', () => {
    expect(SurfaceDataSource.aggregate([SurfaceDataSource.Real, SurfaceDataSource.Fixture])).toBe(SurfaceDataSource.Mixed);
    expect(SurfaceDataSource.aggregate([SurfaceDataSource.Real, SurfaceDataSource.Unknown])).toBe(SurfaceDataSource.Unknown);
    expect(SurfaceDataSource.aggregate([SurfaceDataSource.Unavailable, SurfaceDataSource.Blocked])).toBe(SurfaceDataSource.Unavailable);
    expect(SurfaceDataSource.aggregate([SurfaceDataSource.Real, SurfaceDataSource.Real])).toBe(SurfaceDataSource.Real);
  });
});

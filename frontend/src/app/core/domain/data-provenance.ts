export class SurfaceDataSource {
  static readonly Real = new SurfaceDataSource('REAL', 'Datos operativos', true);
  static readonly Fixture = new SurfaceDataSource('FIXTURE', 'Datos simulados', false);
  static readonly Mixed = new SurfaceDataSource('MIXED', 'Datos mixtos', false);
  static readonly Unavailable = new SurfaceDataSource('UNAVAILABLE', 'Datos no disponibles', false);
  static readonly Blocked = new SurfaceDataSource('BLOCKED', 'Integración bloqueada', false);
  static readonly Unknown = new SurfaceDataSource('UNKNOWN', 'Procedencia desconocida', false);
  private static readonly values = [this.Real, this.Fixture, this.Mixed, this.Unavailable, this.Blocked];

  private constructor(readonly wire: string, readonly label: string, readonly productionReady: boolean) {}

  static fromWire(raw: unknown): SurfaceDataSource {
    return this.values.find(value => value.wire === raw) ?? this.Unknown;
  }

  static aggregate(required: readonly SurfaceDataSource[]): SurfaceDataSource {
    if (required.length === 0 || required.includes(this.Unknown)) return this.Unknown;
    if (required.includes(this.Unavailable)) return this.Unavailable;
    if (required.includes(this.Blocked)) return this.Blocked;
    if (required.every(source => source === this.Real)) return this.Real;
    if (required.every(source => source === this.Fixture)) return this.Fixture;
    return required.every(source => [this.Real, this.Fixture, this.Mixed].includes(source)) ? this.Mixed : this.Unknown;
  }
}

export class PersistenceReadiness {
  static readonly PostgresqlRequired = new PersistenceReadiness('POSTGRESQL_REQUIRED', 'PostgreSQL requerido', SurfaceDataSource.Unavailable, false);
  static readonly Durable = new PersistenceReadiness('POSTGRESQL', 'Persistencia durable', SurfaceDataSource.Real, true);
  static readonly MemoryResettable = new PersistenceReadiness('MEMORY', 'Memoria reiniciable', SurfaceDataSource.Fixture, false);
  static readonly BlockedStoreCoreLive = new PersistenceReadiness('BLOCKED_STORECORE_LIVE', 'StoreCore live bloqueado', SurfaceDataSource.Blocked, false);
  static readonly Unknown = new PersistenceReadiness('UNKNOWN', 'Persistencia desconocida', SurfaceDataSource.Unknown, false);
  private static readonly wireValues = [this.Durable, this.MemoryResettable];

  private constructor(readonly wire: string, readonly label: string, readonly source: SurfaceDataSource, readonly productionReady: boolean) {}

  static fromWire(raw: unknown): PersistenceReadiness {
    const normalized = typeof raw === 'string' ? raw.toUpperCase() : raw;
    return this.wireValues.find(value => value.wire === normalized) ?? this.Unknown;
  }
}

import { SurfaceDataSource } from './data-provenance';

export class CatalogSource {
  static readonly StoreCoreLive = new CatalogSource('STORECORE_LIVE', 'StoreCore conectado', true, false, SurfaceDataSource.Real);
  static readonly StoreCoreCache = new CatalogSource('STORECORE_CACHE', 'Último catálogo sincronizado', true, false, SurfaceDataSource.Mixed);
  static readonly LocalFixture = new CatalogSource('LOCAL_FIXTURE', 'Simulador local', true, true, SurfaceDataSource.Fixture);
  static readonly Unavailable = new CatalogSource('UNAVAILABLE', 'Fuente no disponible', false, false, SurfaceDataSource.Unavailable);
  static readonly Unknown = new CatalogSource('UNKNOWN', 'Fuente desconocida', false, false, SurfaceDataSource.Unknown);
  private static readonly values = [this.StoreCoreLive, this.StoreCoreCache, this.LocalFixture, this.Unavailable];

  private constructor(readonly wire: string, readonly label: string, readonly available: boolean,
    readonly requiresLocalAcceptance: boolean, readonly surfaceSource: SurfaceDataSource) {}

  static fromWire(raw: unknown): CatalogSource {
    return this.values.find(value => value.wire === raw) ?? this.Unknown;
  }
}

export class CatalogFreshness {
  static readonly Current = new CatalogFreshness('CURRENT', 'Vigente', true);
  static readonly Stale = new CatalogFreshness('STALE', 'Requiere actualización', false);
  static readonly Unavailable = new CatalogFreshness('UNAVAILABLE', 'No disponible', false);
  static readonly Unknown = new CatalogFreshness('UNKNOWN', 'Vigencia desconocida', false);

  private constructor(readonly wire: string, readonly label: string, readonly allowsSelection: boolean) {}

  static fromWire(rawStale: unknown, source: CatalogSource): CatalogFreshness {
    if (source === CatalogSource.Unavailable) return this.Unavailable;
    if (rawStale === false) return this.Current;
    if (rawStale === true) return this.Stale;
    return this.Unknown;
  }
}

export class CatalogAcceptanceState {
  static readonly NotRequired = new CatalogAcceptanceState('NOT_REQUIRED', 'Catálogo conectado', true);
  static readonly Required = new CatalogAcceptanceState('REQUIRED', 'Aceptación local requerida', false);
  static readonly Accepted = new CatalogAcceptanceState('ACCEPTED', 'Simulador local aceptado', true);
  static readonly Blocked = new CatalogAcceptanceState('BLOCKED', 'Catálogo no habilitado', false);
  static readonly Unknown = new CatalogAcceptanceState('UNKNOWN', 'Aceptación desconocida', false);

  private constructor(readonly wire: string, readonly label: string, readonly allowsSelection: boolean) {}

  static initial(snapshot: CatalogSnapshot | null): CatalogAcceptanceState {
    if (!catalogIsReadable(snapshot)) return this.Blocked;
    return snapshot?.source.requiresLocalAcceptance ? this.Required : this.NotRequired;
  }

  static accept(current: CatalogAcceptanceState, snapshot: CatalogSnapshot | null): CatalogAcceptanceState {
    return current === this.Required && snapshot?.source === CatalogSource.LocalFixture && catalogIsReadable(snapshot)
      ? this.Accepted
      : current;
  }
}

export interface CatalogItem {
  readonly sku: string;
  readonly name: string;
  readonly variantId: string;
  readonly priceVersion: string | null;
  readonly unitPrice: number | null;
}

export interface CatalogSnapshot {
  readonly version: string | null;
  readonly source: CatalogSource;
  readonly freshness: CatalogFreshness;
  readonly importedAt: string | null;
  readonly validUntil: string | null;
  readonly canonicalPath: string | null;
  readonly items: readonly CatalogItem[];
}

export function catalogIsReadable(snapshot: CatalogSnapshot | null): boolean {
  return snapshot !== null
    && snapshot.source.available
    && snapshot.freshness.allowsSelection
    && snapshot.items.length > 0;
}

export function catalogAllowsSelection(snapshot: CatalogSnapshot | null, acceptance: CatalogAcceptanceState): boolean {
  return catalogIsReadable(snapshot) && acceptance.allowsSelection;
}

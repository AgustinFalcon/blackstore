import { SaleTextV2 } from './sale-text-v2';

/** Opaque route identity. It validates the shared V2 reference contract without changing case or content. */
export class SaleRouteId {
  private static readonly pathPrefix = 'id:';
  private constructor(readonly wire: string) {}

  static fromWire(raw: unknown): SaleRouteId | null {
    if (typeof raw !== 'string') return null;
    try { return new SaleRouteId(SaleTextV2.ref(raw)); }
    catch { return null; }
  }

  static fromPathSegment(raw: unknown): SaleRouteId | null {
    return typeof raw === 'string' && raw.startsWith(this.pathPrefix)
      ? this.fromWire(raw.slice(this.pathPrefix.length)) : null;
  }

  get pathSegment(): string { return `${SaleRouteId.pathPrefix}${this.wire}`; }

  matches(raw: unknown): boolean { return SaleRouteId.fromWire(raw)?.wire === this.wire; }
}

/** Closed lifecycle for resolving a public sale route to one durable operation. */
export class SaleSnapshotState {
  static readonly Idle = new SaleSnapshotState('IDLE', 'Ingresá desde una venta existente', false, false);
  static readonly Loading = new SaleSnapshotState('LOADING', 'Comprobando el ticket…', true, false);
  static readonly Found = new SaleSnapshotState('FOUND', 'Ticket comprobado', false, true);
  static readonly NotFound = new SaleSnapshotState('NOT_FOUND', 'No encontramos un ticket visible con ese identificador', false, false);
  static readonly Ambiguous = new SaleSnapshotState('AMBIGUOUS', 'La venta tiene más de una operación. Elegí la operación desde Ventas existentes', false, false);
  static readonly Invalid = new SaleSnapshotState('INVALID', 'El identificador del ticket no es válido', false, false);
  static readonly Unavailable = new SaleSnapshotState('UNAVAILABLE', 'No se pudo comprobar el ticket. Reintentá la consulta', false, false);
  static readonly Unknown = new SaleSnapshotState('UNKNOWN', 'Estado de consulta desconocido', false, false);
  static readonly values = [SaleSnapshotState.Idle, SaleSnapshotState.Loading, SaleSnapshotState.Found,
    SaleSnapshotState.NotFound, SaleSnapshotState.Ambiguous, SaleSnapshotState.Invalid,
    SaleSnapshotState.Unavailable] as const;

  private constructor(
    readonly wire: string,
    readonly label: string,
    readonly loading: boolean,
    readonly showsDetail: boolean,
  ) {}

  static fromWire(raw: unknown): SaleSnapshotState {
    return SaleSnapshotState.values.find(value => value.wire === raw) ?? SaleSnapshotState.Unknown;
  }
}

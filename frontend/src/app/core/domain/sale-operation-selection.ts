/** Authoritative operation identity accepted from the /ventas route boundary. */
export class OperationId {
  private static readonly pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  private constructor(readonly wire: string) {}

  static fromWire(raw: unknown): OperationId | null {
    return typeof raw === 'string' && OperationId.pattern.test(raw) ? new OperationId(raw.toLowerCase()) : null;
  }

  equals(other: OperationId | null): boolean { return other?.wire === this.wire; }
}

/** Closed translation of queryParamMap.getAll('operationId'). */
export class SaleOperationSelection {
  static readonly None = new SaleOperationSelection('NONE', null, false);
  static readonly Unknown = new SaleOperationSelection('UNKNOWN', null, false);

  private constructor(
    readonly wire: string,
    readonly operationId: OperationId | null,
    readonly isSelected: boolean,
  ) {}

  static fromWire(raw: readonly unknown[]): SaleOperationSelection {
    if (raw.length === 0) return this.None;
    if (raw.length !== 1) return this.Unknown;
    const operationId = OperationId.fromWire(raw[0]);
    return operationId ? new SaleOperationSelection('SELECTED', operationId, true) : this.Unknown;
  }

  equals(other: SaleOperationSelection): boolean {
    return this === other || (this.isSelected && other.isSelected && this.operationId?.equals(other.operationId) === true);
  }
}

/** Closed lifecycle for the selected operation read. */
export class SaleSelectionPhase {
  static readonly Idle = new SaleSelectionPhase('IDLE', 'Elegí una venta para consultar sus operaciones.', false, false, false);
  static readonly Loading = new SaleSelectionPhase('LOADING', 'Comprobando la venta seleccionada…', true, true, false);
  static readonly Ready = new SaleSelectionPhase('READY', 'Venta comprobada.', true, false, false);
  static readonly Unavailable = new SaleSelectionPhase('UNAVAILABLE', 'No se pudo comprobar la venta seleccionada.', true, false, true);
  static readonly Unknown = new SaleSelectionPhase('UNKNOWN', 'La selección de venta no es válida.', true, false, false);
  static readonly values = [this.Idle, this.Loading, this.Ready, this.Unavailable] as const;

  private constructor(
    readonly wire: string,
    readonly label: string,
    readonly showsPanel: boolean,
    readonly loading: boolean,
    readonly canRetry: boolean,
  ) {}

  static fromWire(raw: unknown): SaleSelectionPhase {
    return this.values.find(value => value.wire === raw) ?? this.Unknown;
  }
}

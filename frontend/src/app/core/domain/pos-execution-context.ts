export class PosContextState {
  static readonly Available = new PosContextState('Available', 'Contexto POS comprobado');
  static readonly Unavailable = new PosContextState('Unavailable', 'Contexto POS no disponible');
  static readonly Unknown = new PosContextState('Unknown', 'Contexto POS no comprobado');
  private constructor(readonly wire: string, readonly label: string) {}
  static fromWire(raw: unknown): PosContextState { return [this.Available, this.Unavailable].find(value => value.wire === raw) ?? this.Unknown; }
}
export interface PosExecutionContext { readonly clientInstanceId: string; readonly deviceId: string; readonly terminalId: number; }
export interface PosContextObservation { readonly state: PosContextState; readonly context: PosExecutionContext | null; }

import { DemoRole } from './demo-types';

export class DemoAction {
  private constructor(readonly key: string, readonly label: string, readonly managerOnly = false) {}
  static readonly Read = new DemoAction('read', 'Consultar');
  static readonly Export = new DemoAction('export', 'Exportar');
  static readonly Print = new DemoAction('print', 'Imprimir');
  static readonly EditCart = new DemoAction('edit-cart', 'Editar carrito');
  static readonly SelectCustomer = new DemoAction('select-customer', 'Seleccionar cliente');
  static readonly Checkout = new DemoAction('checkout', 'Cobrar');
  static readonly OpenCash = new DemoAction('open-cash', 'Abrir caja');
  static readonly MoveCash = new DemoAction('move-cash', 'Registrar movimiento');
  static readonly CloseCash = new DemoAction('close-cash', 'Cerrar caja');
  static readonly SaveCustomer = new DemoAction('save-customer', 'Guardar cliente');
  static readonly Reset = new DemoAction('reset', 'Reiniciar escenario');
  static readonly ChangeRole = new DemoAction('change-role', 'Cambiar perfil');
  static readonly Discount = new DemoAction('discount', 'Aplicar o quitar descuento', true);
  static readonly Reverse = new DemoAction('reverse', 'Devolver venta', true);
  static readonly SaveProduct = new DemoAction('save-product', 'Guardar producto', true);
  static readonly AdjustStock = new DemoAction('adjust-stock', 'Ajustar stock', true);
  static readonly Unknown = new DemoAction('unknown', 'Acción no disponible');
  static readonly values = Object.freeze([this.Read, this.Export, this.Print, this.EditCart, this.SelectCustomer, this.Checkout, this.OpenCash, this.MoveCash, this.CloseCash, this.SaveCustomer, this.Reset, this.ChangeRole, this.Discount, this.Reverse, this.SaveProduct, this.AdjustStock]);
  static fromWire(value: unknown): DemoAction { return this.values.find(action => action.key === value) ?? this.Unknown; }
}

export class DemoPermissionDecision {
  private constructor(readonly label: string, readonly allowed: boolean, readonly reason: string) {}
  static readonly Allowed = new DemoPermissionDecision('Permitido', true, '');
  static readonly Denied = new DemoPermissionDecision('No permitido', false, 'Esta acción requiere el perfil Encargada. Cambialo en Escenarios.');
  static readonly Unknown = new DemoPermissionDecision('Permiso no disponible', false, 'Elegí un perfil y una acción conocidos.');
  static fromWire(value: unknown): DemoPermissionDecision { return value === 'allowed' ? this.Allowed : value === 'denied' ? this.Denied : this.Unknown; }
}

export class DemoPermissionPolicy {
  static decide(role: DemoRole, action: DemoAction): DemoPermissionDecision {
    if (!DemoRole.values.includes(role) || !DemoAction.values.includes(action)) return DemoPermissionDecision.Unknown;
    return action.managerOnly && role !== DemoRole.Manager ? DemoPermissionDecision.Denied : DemoPermissionDecision.Allowed;
  }
}

export class DemoPermissionError extends Error {
  constructor(readonly decision: DemoPermissionDecision) { super(decision.reason); }
}

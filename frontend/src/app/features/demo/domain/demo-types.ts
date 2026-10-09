export class DemoPaymentMethod {
  private constructor(readonly key: string, readonly label: string, readonly isCash = false) {}
  static readonly Cash = new DemoPaymentMethod('cash', 'Efectivo', true);
  static readonly Card = new DemoPaymentMethod('card', 'Tarjeta');
  static readonly Transfer = new DemoPaymentMethod('transfer', 'Transferencia');
  static readonly QR = new DemoPaymentMethod('qr', 'QR');
  static readonly Unknown = new DemoPaymentMethod('unknown', 'Medio no disponible');
  static readonly values = [this.Cash, this.Card, this.Transfer, this.QR];
  static fromWire(value: unknown): DemoPaymentMethod { return this.values.find(item => item.key === value) ?? this.Unknown; }
}
export class DemoPaymentStatus {
  private constructor(readonly key: string, readonly label: string, readonly pending = false, readonly approved = false, readonly retryable = false) {}
  static readonly Pending = new DemoPaymentStatus('pending', 'Esperando confirmación', true);
  static readonly Approved = new DemoPaymentStatus('approved', 'Pago aprobado', false, true);
  static readonly Rejected = new DemoPaymentStatus('rejected', 'Pago rechazado', false, false, true);
  static readonly Cancelled = new DemoPaymentStatus('cancelled', 'Pago cancelado', false, false, true);
  static readonly Unknown = new DemoPaymentStatus('unknown', 'Pago no disponible');
  static readonly values = [this.Pending, this.Approved, this.Rejected, this.Cancelled];
  static fromWire(value: unknown): DemoPaymentStatus { return this.values.find(item => item.key === value) ?? this.Unknown; }
}
export class DemoProductArt {
  private constructor(readonly key: string, readonly asset: string) {}
  static readonly Apparel = new DemoProductArt('shirt', '/assets/demo/shirt.svg');
  static readonly Pants = new DemoProductArt('pants', '/assets/demo/pants.svg');
  static readonly Shorts = new DemoProductArt('shorts', '/assets/demo/shorts.svg');
  static readonly Shoes = new DemoProductArt('shoe', '/assets/demo/shoe.svg');
  static readonly Accessories = new DemoProductArt('bag', '/assets/demo/bag.svg');
  static readonly Cap = new DemoProductArt('cap', '/assets/demo/cap.svg');
  static readonly Belt = new DemoProductArt('belt', '/assets/demo/belt.svg');
  static readonly Basics = new DemoProductArt('socks', '/assets/demo/socks.svg');
  static readonly Unknown = new DemoProductArt('unknown', '/assets/demo/product.svg');
  static fromWire(value: unknown): DemoProductArt { return [this.Apparel,this.Pants,this.Shorts,this.Shoes,this.Accessories,this.Cap,this.Belt,this.Basics].find(item=>item.key===value)??this.Unknown; }
}
export interface DemoPaymentAttempt { readonly id: string; readonly method: DemoPaymentMethod; readonly status: DemoPaymentStatus; readonly total: number; readonly cashAmount: number; readonly received: number; readonly reference: string; readonly saleId?: string; }
export class DemoSaleStatus {
  private constructor(readonly key: string, readonly label: string, readonly canReverse = false) {}
  static readonly Completed = new DemoSaleStatus('completed', 'Completada', true);
  static readonly Reversed = new DemoSaleStatus('reversed', 'Devuelta');
  static readonly Unknown = new DemoSaleStatus('unknown', 'Estado no disponible');
  static readonly values = [this.Completed, this.Reversed];
  static fromWire(value: unknown): DemoSaleStatus { return this.values.find(item => item.key === value) ?? this.Unknown; }
}
export class DemoCashStatus {
  private constructor(readonly key: string, readonly label: string, readonly canSell = false) {}
  static readonly Open = new DemoCashStatus('open', 'Caja abierta', true);
  static readonly Closed = new DemoCashStatus('closed', 'Caja cerrada');
  static readonly Unknown = new DemoCashStatus('unknown', 'Caja no disponible');
  static fromWire(value: unknown): DemoCashStatus { return [this.Open, this.Closed].find(item => item.key === value) ?? this.Unknown; }
}
export class DemoRole {
  private constructor(readonly key: string, readonly label: string, readonly canManage = false) {}
  static readonly Manager = new DemoRole('manager', 'Encargada', true);
  static readonly Cashier = new DemoRole('cashier', 'Cajero');
  static readonly Unknown = new DemoRole('unknown', 'Perfil no disponible');
  static readonly values = [this.Manager, this.Cashier];
  static fromWire(value: unknown): DemoRole { return this.values.find(item => item.key === value) ?? this.Unknown; }
}
export class DemoScenario {
  private constructor(readonly key: string, readonly label: string, readonly closed = false, readonly empty = false, readonly low = false, readonly fail = false, readonly slow = false) {}
  static readonly Normal = new DemoScenario('normal', 'Comercio en marcha');
  static readonly Closed = new DemoScenario('closed', 'Caja cerrada', true);
  static readonly Empty = new DemoScenario('empty', 'Sin ventas', false, true);
  static readonly Low = new DemoScenario('low', 'Stock bajo', false, false, true);
  static readonly Error = new DemoScenario('error', 'Error recuperable al cobrar', false, false, false, true);
  static readonly Slow = new DemoScenario('slow', 'Carga lenta', false, false, false, false, true);
  static readonly Unknown = new DemoScenario('unknown', 'Escenario no disponible');
  static readonly values = [this.Normal, this.Closed, this.Empty, this.Low, this.Error, this.Slow];
  static fromWire(value: unknown): DemoScenario { return this.values.find(item => item.key === value) ?? this.Unknown; }
}
export class DemoMovementKind {
  private constructor(readonly key: string, readonly label: string, readonly sign: number) {}
  static readonly Opening = new DemoMovementKind('opening', 'Apertura', 1);
  static readonly Sale = new DemoMovementKind('sale', 'Venta', 1);
  static readonly Income = new DemoMovementKind('income', 'Ingreso', 1);
  static readonly Expense = new DemoMovementKind('expense', 'Egreso', -1);
  static readonly Refund = new DemoMovementKind('refund', 'Devolución', -1);
  static readonly Unknown = new DemoMovementKind('unknown', 'Movimiento no disponible', 0);
  static readonly values = [this.Opening, this.Sale, this.Income, this.Expense, this.Refund];
  static fromWire(value: unknown): DemoMovementKind { return this.values.find(item => item.key === value) ?? this.Unknown; }
}
export class DemoViewState {
  private constructor(readonly label: string, readonly ready = false) {}
  static readonly Ready = new DemoViewState('Listo', true);
  static readonly Loading = new DemoViewState('Cargando datos de demostración…');
  static readonly Unknown = new DemoViewState('Vista no disponible');
  static fromWire(value: unknown): DemoViewState { return value === 'ready' ? this.Ready : value === 'loading' ? this.Loading : this.Unknown; }
}
export const money = (cents: number): string => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(cents / 100);
export interface Product { readonly id: string; readonly name: string; readonly sku: string; readonly category: string; readonly art: DemoProductArt; readonly price: number; readonly cost?: number; readonly stock: number; readonly active: boolean; readonly version: number; readonly icon: string; }
export interface Customer { readonly id: string; readonly name: string; readonly email: string; readonly phone: string; }
export interface CartLine { readonly productId: string; readonly quantity: number; readonly version: number; readonly price: number; }
export interface SaleLine { readonly productId: string; readonly name: string; readonly sku: string; readonly category: string; readonly quantity: number; readonly price: number; readonly cost?: number; }
export interface Sale { readonly id: string; readonly date: string; readonly customerId: string; readonly customerName: string; readonly lines: readonly SaleLine[]; readonly total: number; readonly discount: number; readonly method: DemoPaymentMethod; readonly cashAmount: number; readonly received: number; readonly reference: string; readonly status: DemoSaleStatus; readonly cashier: string; readonly reversalReason?: string; }
export interface Movement { readonly id: string; readonly date: string; readonly kind: DemoMovementKind; readonly amount: number; readonly reason: string; }
export interface StockMovement { readonly id: string; readonly productId: string; readonly date: string; readonly quantity: number; readonly reason: string; }
export interface DemoSnapshot { readonly products: readonly Product[]; readonly customers: readonly Customer[]; readonly cart: readonly CartLine[]; readonly customerId: string; readonly discount: number; readonly sales: readonly Sale[]; readonly movements: readonly Movement[]; readonly stockMovements: readonly StockMovement[]; readonly cashStatus: DemoCashStatus; readonly role: DemoRole; readonly scenario: DemoScenario; readonly payment?: DemoPaymentAttempt; readonly cashCount?: {readonly declared: number; readonly expected: number; readonly reason: string}; }

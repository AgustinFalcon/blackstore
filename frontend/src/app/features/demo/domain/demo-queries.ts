import { DemoMovementKind, DemoPaymentMethod, DemoSnapshot, DemoStockFilter, Sale } from './demo-types';
import { DemoDateRange } from './demo-calendar';

export interface SaleFilter { readonly search: string; readonly method: DemoPaymentMethod | null; readonly status: import('./demo-types').DemoSaleStatus | null; readonly period: DemoDateRange; }
/** Pure read model for the demo: all report formulas share the sale snapshots. */
export class DemoQueries {
  constructor(private readonly snapshot: DemoSnapshot) {}
  sales(filter: SaleFilter): readonly Sale[] {
    const query = filter.search.toLowerCase();
    return this.snapshot.sales.filter(s => `${s.id} ${s.customerName}`.toLowerCase().includes(query) && (!filter.method || s.method === filter.method) && (!filter.status || s.status === filter.status) && filter.period.includes(s.date));
  }
  total(sales: readonly Sale[]): number { return sales.reduce((sum, s) => sum + (s.status.canReverse ? s.total : 0), 0); }
  completed(sales: readonly Sale[]): number { return sales.filter(s => s.status.canReverse).length; }
  average(sales: readonly Sale[]): number { const count = this.completed(sales); return count ? Math.round(this.total(sales) / count) : 0; }
  expenses(period: DemoDateRange): number { return this.snapshot.movements.filter(m => m.kind === DemoMovementKind.Expense && period.includes(m.date)).reduce((sum, m) => sum + m.amount, 0); }
  byMethod(sales: readonly Sale[], method: DemoPaymentMethod): number { return sales.filter(s=>s.status.canReverse).reduce((sum,s)=>sum+(method.isCash?s.cashAmount:s.method===method?s.total-s.cashAmount:0),0); }
  byCategory(sales: readonly Sale[], category: string): number {
    return sales.filter(s => s.status.canReverse).reduce((sum, sale) => {
      const subtotal=sale.lines.reduce((value,line)=>value+line.price*line.quantity,0);
      if(!subtotal)return sum;
      let cumulative=0n, previous=0n, categoryTotal=0;
      for(const line of sale.lines){
        cumulative+=BigInt(line.price)*BigInt(line.quantity);
        const allocated=(cumulative*BigInt(sale.total)+BigInt(Math.floor(subtotal/2)))/BigInt(subtotal);
        if(line.category===category)categoryTotal+=Number(allocated-previous);
        previous=allocated;
      }
      return sum+categoryTotal;
    },0);
  }
  topProducts(sales: readonly Sale[]): {name: string; quantity: number}[] { const items = new Map<string, number>(); sales.filter(s => s.status.canReverse).forEach(s => s.lines.forEach(l => items.set(l.name, (items.get(l.name) ?? 0) + l.quantity))); return [...items].map(([name, quantity]) => ({name, quantity})).sort((a, b) => b.quantity - a.quantity).slice(0, 5); }
  lowStock(): number { return this.snapshot.products.filter(p=>DemoStockFilter.LowActive.matches(p)).length; }
  cartUnits(): number { return this.snapshot.cart.reduce((sum, line) => sum + line.quantity, 0); }
  currentCashMovements() { const start = this.snapshot.movements.map(m => m.kind).lastIndexOf(DemoMovementKind.Opening); return this.snapshot.movements.slice(Math.max(start, 0)); }
}

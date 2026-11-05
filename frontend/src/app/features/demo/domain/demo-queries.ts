import { DemoCashClosure, DemoCashShift, DemoMovementKind, DemoPaymentMethod, DemoSnapshot, DemoStockFilter, Sale } from './demo-types';
import { DemoDateRange } from './demo-calendar';
import { DemoMoney } from './demo-money';

export interface SaleFilter { readonly search: string; readonly method: DemoPaymentMethod | null; readonly status: import('./demo-types').DemoSaleStatus | null; readonly period: DemoDateRange; }
/** Pure read model for the demo: all report formulas share the sale snapshots. */
export class DemoQueries {
  constructor(private readonly snapshot: DemoSnapshot) {}
  sales(filter: SaleFilter): readonly Sale[] {
    const query = filter.search.toLowerCase();
    return this.snapshot.sales.filter(s => `${s.id} ${s.customerName}`.toLowerCase().includes(query) && (!filter.method || s.method === filter.method) && (!filter.status || s.status === filter.status) && filter.period.includes(s.date));
  }
  total(sales: readonly Sale[]): number { return DemoMoney.sum(sales.filter(s=>s.status.canReverse).map(s=>s.total)); }
  completed(sales: readonly Sale[]): number { return sales.filter(s => s.status.canReverse).length; }
  average(sales: readonly Sale[]): number { const count = BigInt(this.completed(sales)); return count ? DemoMoney.number((DemoMoney.integer(this.total(sales))+count/2n)/count) : 0; }
  expenses(period: DemoDateRange): number { return DemoMoney.sum(this.snapshot.movements.filter(m => m.kind === DemoMovementKind.Expense && period.includes(m.date)).map(m=>m.amount)); }
  byMethod(sales: readonly Sale[], method: DemoPaymentMethod): number {
    return DemoMoney.sum(sales.filter(s=>s.status.canReverse).map(s=>{
      const electronic=DemoMoney.number(DemoMoney.integer(s.total)-DemoMoney.integer(s.cashAmount));
      return method.isCash?s.cashAmount:s.method===method?electronic:0;
    }));
  }
  byCategory(sales: readonly Sale[], category: string): number {
    const total=sales.filter(s => s.status.canReverse).reduce((sum, sale) => {
      const subtotal=DemoMoney.integer(DemoMoney.subtotal(sale.lines));
      if(!subtotal)return sum;
      let cumulative=0n, previous=0n, categoryTotal=0n;
      for(const line of sale.lines){
        cumulative+=DemoMoney.integer(line.price)*DemoMoney.integer(line.quantity);
        const allocated=(cumulative*DemoMoney.integer(sale.total)+subtotal/2n)/subtotal;
        if(line.category===category)categoryTotal+=allocated-previous;
        previous=allocated;
      }
      return sum+categoryTotal;
    },0n);
    return DemoMoney.number(total);
  }
  categories(sales: readonly Sale[]): readonly string[] { return [...new Set(sales.filter(s=>s.status.canReverse).flatMap(s=>s.lines.map(line=>line.category)))].sort(); }
  topProducts(sales: readonly Sale[]): {productId: string; sku: string; name: string; quantity: number}[] {
    const items = new Map<string, {productId: string; sku: string; name: string; quantity: number}>();
    // Identity survives catalogue renames; labels come only from receipt snapshots.
    for(const sale of sales.filter(s=>s.status.canReverse)) for(const line of sale.lines) {
      const previous=items.get(line.productId);
      items.set(line.productId,{productId:line.productId,sku:previous?.sku??line.sku,name:previous?.name??line.name,quantity:(previous?.quantity??0)+line.quantity});
    }
    return [...items.values()].sort((a,b)=>b.quantity-a.quantity||a.productId.localeCompare(b.productId)).slice(0,5);
  }
  lowStock(): number { return this.snapshot.products.filter(p=>DemoStockFilter.LowActive.matches(p)).length; }
  cartUnits(): number { return this.snapshot.cart.reduce((sum, line) => sum + line.quantity, 0); }
  currentCashMovements() { const start = this.snapshot.movements.map(m => m.kind).lastIndexOf(DemoMovementKind.Opening); return this.snapshot.movements.slice(Math.max(start, 0)); }
  currentCashShift(): DemoCashShift | undefined {
    const movements=this.currentCashMovements(), opening=movements[0];
    if(opening?.kind!==DemoMovementKind.Opening)return undefined;
    const expected=DemoMoney.balance(movements);
    return Object.freeze({id:opening.id,openedAt:opening.date,opening:opening.amount,movements:Object.freeze(movements),expected,status:this.snapshot.cashStatus});
  }
  cashHistory(): readonly DemoCashClosure[] { return this.snapshot.cashClosures; }
}

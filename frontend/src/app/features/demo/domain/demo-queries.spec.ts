import { completePayment } from '../infrastructure/payment-test.fixture';
import { DemoQueries } from './demo-queries';
import { DemoCashStatus, DemoMovementKind, DemoPaymentMethod, DemoPaymentStatus, DemoProductStatus, DemoSaleStatus, DemoScenario, money } from './demo-types';
import { DemoDateRange } from './demo-calendar';
import { DemoMoney } from './demo-money';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';
describe('demo monetary read model', () => {
  it('keeps exact cents at the safe boundary and rejects invalid money at conversion', () => {
    const maximum=Number.MAX_SAFE_INTEGER;
    expect(DemoMoney.subtotal([{price:maximum-1,quantity:1},{price:1,quantity:1}])).toBe(maximum);
    expect(DemoMoney.discounted([{price:maximum,quantity:1}],30)).toBe(6305039478318694);
    expect(()=>DemoMoney.subtotal([{price:maximum,quantity:2}])).toThrowError(/rango entero seguro/);
    expect(()=>DemoMoney.sum([maximum,1])).toThrowError(/rango entero seguro/);
    for(const invalid of [NaN,Infinity,-1,0.5,maximum+1])expect(()=>DemoMoney.integer(invalid)).toThrowError(/rango entero seguro/);
    expect(()=>DemoMoney.number(-1n)).toThrowError(/rango entero seguro/);
    expect(DemoMoney.decimal(maximum)).toBe('90071992547409.91');
    expect(money(maximum)).toBe('$ 90.071.992.547.409,91');
    expect(money(-101)).toBe('-$ 1,01');
  });
  it('retains immutable closings and their ledger across reopening, movements and later closings', () => {
    const repo=new LocalDemoRepository(()=>new Date('2026-10-21T14:00:00Z'));
    const historyBefore=repo.snapshot().cashClosures.length;
    const firstShift=new DemoQueries(repo.snapshot()).currentCashShift()!;
    repo.closeCash(firstShift.expected-1,'Faltante contado');
    const closing=repo.snapshot().cashClosures[historyBefore];
    expect(closing.id).toBe(firstShift.id);expect(closing.opening).toBe(firstShift.opening);
    expect(closing.status).toBe(DemoCashStatus.Closed);expect(closing.declared).toBe(firstShift.expected-1);
    expect(Object.isFrozen(closing)).toBeTrue();expect(Object.isFrozen(closing.movements)).toBeTrue();
    expect(Object.isFrozen(closing.movements[0])).toBeTrue();
    const count=repo.snapshot().cashCount;
    repo.openCash(100);repo.moveCash(DemoMovementKind.Income,25,'Ingreso siguiente turno');
    const queries=new DemoQueries(repo.snapshot());
    expect(queries.currentCashShift()?.id).not.toBe(closing.id);
    expect(queries.currentCashShift()?.expected).toBe(125);expect(queries.currentCashMovements().length).toBe(2);
    expect(queries.cashHistory()[historyBefore]).toBe(closing);expect(closing.expected).toBe(firstShift.expected);
    expect(repo.snapshot().cashCount).toBe(count);
    repo.closeCash(125,'');expect(repo.snapshot().cashClosures.length).toBe(historyBefore+2);
    expect(repo.snapshot().cashClosures[historyBefore]).toBe(closing);
  });
  it('rejects aggregate cash overflow before publishing or allocating movement IDs', () => {
    const repo=new LocalDemoRepository();const before=repo.snapshot();let publications=0;repo.subscribe(()=>publications++);
    expect(()=>repo.moveCash(DemoMovementKind.Income,Number.MAX_SAFE_INTEGER,'Overflow')).toThrowError(/rango entero seguro/);
    expect(repo.snapshot()).toBe(before);expect(publications).toBe(1);
    repo.moveCash(DemoMovementKind.Income,Number.MAX_SAFE_INTEGER-repo.expectedCash(),'Hasta el límite');
    expect(repo.snapshot().movements.at(-1)?.id).toBe('101');expect(repo.expectedCash()).toBe(Number.MAX_SAFE_INTEGER);
    const maximum=repo.snapshot();expect(()=>repo.moveCash(DemoMovementKind.Income,1,'Un centavo más')).toThrow();expect(repo.snapshot()).toBe(maximum);
    repo.moveCash(DemoMovementKind.Expense,1,'Dejar lugar');expect(repo.snapshot().movements.at(-1)?.id).toBe('102');
    expect(repo.expectedCash()).toBe(Number.MAX_SAFE_INTEGER-1);
  });
  it('rechecks aggregate cash at payment approval without consuming sale or event IDs', () => {
    const repo=new LocalDemoRepository();repo.cart('p1',1);
    repo.beginPayment('pending-overflow',DemoPaymentMethod.Card,1,'Mixto',1);
    repo.moveCash(DemoMovementKind.Income,Number.MAX_SAFE_INTEGER-repo.expectedCash(),'Hasta el límite');
    const before=repo.snapshot();let publications=0;repo.subscribe(()=>publications++);
    expect(()=>repo.resolvePayment('pending-overflow',DemoPaymentStatus.Approved)).toThrowError(/rango entero seguro/);
    expect(repo.snapshot()).toBe(before);expect(publications).toBe(1);expect(before.payment?.status).toBe(DemoPaymentStatus.Pending);
    repo.moveCash(DemoMovementKind.Expense,1,'Dejar lugar');repo.resolvePayment('pending-overflow',DemoPaymentStatus.Approved);
    expect(repo.snapshot().sales[0].id).toBe('V-103');expect(repo.expectedCash()).toBe(Number.MAX_SAFE_INTEGER);
  });
  it('reports categories and product identity from receipts after catalogue changes', () => {
    const repo=new LocalDemoRepository();const first=repo.snapshot().products[0],second=repo.snapshot().products[1];
    repo.saveProduct({...first,name:'Mismo nombre',category:'Categoría histórica'});
    repo.saveProduct({...second,name:'Mismo nombre',category:'Otra histórica'});
    repo.cart(first.id,1);repo.cart(second.id,1);const old=completePayment(repo,'original',DemoPaymentMethod.Card,0,'');
    repo.saveProduct({...repo.snapshot().products[0],name:'Nombre nuevo',category:'Categoría actual'});
    repo.cart(first.id,1);const latest=completePayment(repo,'renamed',DemoPaymentMethod.Card,0,'');
    const sales=[latest,old],queries=new DemoQueries(repo.snapshot());
    expect(queries.categories(sales)).toEqual(['Categoría actual','Categoría histórica','Otra histórica']);
    expect(queries.byCategory(sales,'Categoría histórica')).toBe(first.price);
    expect(queries.topProducts(sales)).toEqual([
      {productId:first.id,sku:first.sku,name:'Nombre nuevo',quantity:2},
      {productId:second.id,sku:second.sku,name:'Mismo nombre',quantity:1}
    ]);
    repo.reverse(latest.id,'Devolver');const filtered=repo.snapshot().sales.filter(s=>sales.some(original=>original.id===s.id));
    expect(new DemoQueries(repo.snapshot()).categories(filtered)).toEqual(['Categoría histórica','Otra histórica']);
  });
  it('rejects the exact seeded reversal overflow without publishing or consuming event IDs', () => {
    const repo=new LocalDemoRepository();repo.adjust('p1',Number.MAX_SAFE_INTEGER-2,'maximum');const before=repo.snapshot();let publications=0;repo.subscribe(()=>publications++);
    expect(()=>repo.reverse('V-90','overflow')).toThrowError(/rango entero seguro/);expect(repo.snapshot()).toBe(before);expect(publications).toBe(1);
    repo.adjust('p1',-1,'make room');expect(repo.snapshot().stockMovements[0].id).toBe('102');repo.reverse('V-90','valid');expect(repo.snapshot().products[0].stock).toBe(Number.MAX_SAFE_INTEGER);expect(repo.snapshot().sales.find(s=>s.id==='V-90')?.status).toBe(DemoSaleStatus.Reversed);
  });
  it('prevalidates all lines so a later overflow preserves earlier stock, cash, sale and ID sequence', () => {
    const repo=new LocalDemoRepository();repo.cart('p1',1);repo.cart('p2',1);const sale=completePayment(repo,'multiline',DemoPaymentMethod.Cash,repo.total(),'');
    repo.adjust('p2',Number.MAX_SAFE_INTEGER-repo.snapshot().products[1].stock,'maximum');const before=repo.snapshot();const cash=repo.expectedCash();let publications=0;repo.subscribe(()=>publications++);
    expect(()=>repo.reverse(sale.id,'second line overflow')).toThrowError(/rango entero seguro/);expect(repo.snapshot()).toBe(before);expect(repo.expectedCash()).toBe(cash);expect(publications).toBe(1);expect(repo.snapshot().sales[0].status).toBe(DemoSaleStatus.Completed);
    repo.adjust('p2',-1,'make room');expect(repo.snapshot().stockMovements[0].id).toBe('106');repo.reverse(sale.id,'valid');expect(repo.snapshot().products[0].stock).toBe(2);expect(repo.snapshot().products[1].stock).toBe(Number.MAX_SAFE_INTEGER);expect(repo.expectedCash()).toBe(cash-sale.total);
  });
  it('seeds chronological cash shifts and excludes older sales from the current expected cash', () => {
    const repo=new LocalDemoRepository(()=>new Date('2026-10-21T02:59:00Z'));const queries=new DemoQueries(repo.snapshot());
    const movements=repo.snapshot().movements;
    for(let i=1;i<movements.length;i++)expect(movements[i].date>=movements[i-1].date).toBeTrue();
    const current=queries.currentCashMovements();expect(current[0].kind).toBe(DemoMovementKind.Opening);
    expect(current.filter(m=>m.kind===DemoMovementKind.Sale).map(m=>m.reason)).toEqual(['Venta V-90']);
    expect(current.every(m=>m.date>=current[0].date)).toBeTrue();expect(repo.expectedCash()).toBe(5000000+repo.snapshot().sales[0].total);
    repo.closeCash(repo.expectedCash(),'');expect(repo.snapshot().cashCount?.declared).toBe(repo.snapshot().cashCount?.expected);
  });
  it('can remove a deactivated cart line while additions and checkout stay strict', () => {
    const repo=new LocalDemoRepository();const p=repo.snapshot().products[0];repo.cart(p.id,1);repo.saveProduct({...p,status:DemoProductStatus.Inactive});
    const before=repo.snapshot();expect(()=>repo.cart(p.id,2)).toThrow();expect(()=>completePayment(repo,'inactive',DemoPaymentMethod.Card,0,'')).toThrow();expect(repo.snapshot()).toBe(before);
    repo.cart(p.id,0);expect(repo.snapshot().cart.length).toBe(0);
  });
  it('allocates integer cents so category reports reconcile to the discounted receipt', () => {
    const repo=new LocalDemoRepository();const first=repo.snapshot().products[0],second=repo.snapshot().products[6];
    repo.saveProduct({...first,price:101});repo.saveProduct({...second,price:102});repo.cart(first.id,1);repo.cart(second.id,1);repo.discount(10);
    const sale=completePayment(repo,'rounding',DemoPaymentMethod.Card,0,'');const queries=new DemoQueries(repo.snapshot());
    const a=queries.byCategory([sale],first.category),b=queries.byCategory([sale],second.category);
    expect(Number.isSafeInteger(a)).toBeTrue();expect(Number.isSafeInteger(b)).toBeTrue();expect(a+b).toBe(sale.total);expect(sale.total).toBe(183);
  });
  it('rejects unknown product state and monetary overflow without creating a sale', () => {
    const repo=new LocalDemoRepository();const p=repo.snapshot().products[0];const before=repo.snapshot();
    expect(()=>repo.saveProduct({...p,status:DemoProductStatus.Unknown})).toThrow();expect(repo.snapshot()).toBe(before);
    repo.saveProduct({...p,price:Number.MAX_SAFE_INTEGER});const pending=repo.snapshot();let publications=0;repo.subscribe(()=>publications++);
    expect(()=>repo.cart(p.id,2)).toThrowError(/rango entero seguro/);expect(repo.snapshot()).toBe(pending);expect(publications).toBe(1);
    expect(repo.total()).toBe(0);
  });
  it('rejects a later cart line when individually safe lines exceed the monetary limit', () => {
    const repo=new LocalDemoRepository();const p=repo.snapshot().products[0];repo.saveProduct({...p,price:Number.MAX_SAFE_INTEGER});repo.cart(p.id,1);
    const before=repo.snapshot();expect(()=>repo.cart('p2',1)).toThrowError(/rango entero seguro/);expect(repo.snapshot()).toBe(before);expect(repo.total()).toBe(Number.MAX_SAFE_INTEGER);
    repo.discount(30);expect(repo.total()).toBe(Number((BigInt(Number.MAX_SAFE_INTEGER)*70n+50n)/100n));
  });
  it('rejects report aggregate overflow before a payment attempt publishes or consumes IDs', () => {
    const repo=new LocalDemoRepository();repo.reset(DemoScenario.Empty);const p=repo.snapshot().products[0];repo.saveProduct({...p,price:Number.MAX_SAFE_INTEGER});repo.cart(p.id,1);
    completePayment(repo,'maximum',DemoPaymentMethod.Card,0,'');repo.saveProduct({...repo.snapshot().products[0],price:1});repo.cart(p.id,1);
    const before=repo.snapshot();let publications=0;repo.subscribe(()=>publications++);
    expect(()=>repo.beginPayment('too-much',DemoPaymentMethod.Card,0,'')).toThrowError(/rango entero seguro/);expect(repo.snapshot()).toBe(before);expect(publications).toBe(1);
    repo.reverse(repo.snapshot().sales[0].id,'Make room');completePayment(repo,'too-much',DemoPaymentMethod.Card,0,'');expect(repo.snapshot().sales[0].id).toBe('V-104');
  });
  it('fails closed for unsafe totals, method and category aggregates, expenses and shift balances', () => {
    const snapshot=new LocalDemoRepository().snapshot();const original=snapshot.sales[0];
    const sale={...original,total:Number.MAX_SAFE_INTEGER,cashAmount:0,method:DemoPaymentMethod.Card,lines:[{...original.lines[0],price:Number.MAX_SAFE_INTEGER}]};
    const queries=new DemoQueries({...snapshot,sales:[sale,sale]});
    expect(()=>queries.total([sale,sale])).toThrowError(/rango entero seguro/);
    expect(()=>queries.byMethod([sale,sale],DemoPaymentMethod.Card)).toThrowError(/rango entero seguro/);
    expect(()=>queries.byCategory([sale,sale],sale.lines[0].category)).toThrowError(/rango entero seguro/);
    const amount=Number.MAX_SAFE_INTEGER,base=snapshot.movements[0];
    const expenses=[{...base,kind:DemoMovementKind.Expense,amount},{...base,kind:DemoMovementKind.Expense,amount}];
    expect(()=>new DemoQueries({...snapshot,movements:expenses}).expenses(DemoDateRange.All)).toThrowError(/rango entero seguro/);
    const movements=[{...base,kind:DemoMovementKind.Opening,amount},{...base,kind:DemoMovementKind.Income,amount:1}];
    expect(()=>new DemoQueries({...snapshot,movements}).currentCashShift()).toThrowError(/rango entero seguro/);
  });
  it('rounds the average from exact cents instead of a floating division near the limit', () => {
    const snapshot=new LocalDemoRepository().snapshot(),original=snapshot.sales[0],queries=new DemoQueries(snapshot);
    const sales=[{...original,total:Number.MAX_SAFE_INTEGER-2},{...original,total:1},{...original,total:1}];
    expect(queries.average(sales)).toBe(3002399751580330);
  });
  it('rejects cumulative expense overflow before publishing even after replenishing cash', () => {
    const repo=new LocalDemoRepository();repo.moveCash(DemoMovementKind.Income,Number.MAX_SAFE_INTEGER-repo.expectedCash(),'maximum');repo.moveCash(DemoMovementKind.Expense,Number.MAX_SAFE_INTEGER,'expense');repo.moveCash(DemoMovementKind.Income,1,'replenish');
    const before=repo.snapshot();expect(()=>repo.moveCash(DemoMovementKind.Expense,1,'overflow')).toThrowError(/rango entero seguro/);expect(repo.snapshot()).toBe(before);
  });
});

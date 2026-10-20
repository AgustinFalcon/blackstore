import { completePayment } from '../infrastructure/payment-test.fixture';
import { DemoQueries } from './demo-queries';
import { DemoMovementKind, DemoPaymentMethod, DemoProductStatus, DemoSaleStatus } from './demo-types';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';
describe('demo monetary read model', () => {
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
    repo.saveProduct({...p,price:Number.MAX_SAFE_INTEGER});repo.cart(p.id,2);const pending=repo.snapshot();
    expect(()=>completePayment(repo,'overflow',DemoPaymentMethod.Card,0,'')).toThrow();expect(repo.snapshot()).toBe(pending);
  });
});

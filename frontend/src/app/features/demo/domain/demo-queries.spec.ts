import { DemoQueries } from './demo-queries';
import { DemoMovementKind, DemoPaymentMethod, DemoProductStatus } from './demo-types';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';
describe('demo monetary read model', () => {
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
    const before=repo.snapshot();expect(()=>repo.cart(p.id,2)).toThrow();expect(()=>repo.checkout('inactive',DemoPaymentMethod.Card,0,'')).toThrow();expect(repo.snapshot()).toBe(before);
    repo.cart(p.id,0);expect(repo.snapshot().cart.length).toBe(0);
  });
  it('allocates integer cents so category reports reconcile to the discounted receipt', () => {
    const repo=new LocalDemoRepository();const first=repo.snapshot().products[0],second=repo.snapshot().products[6];
    repo.saveProduct({...first,price:101});repo.saveProduct({...second,price:102});repo.cart(first.id,1);repo.cart(second.id,1);repo.discount(10);
    const sale=repo.checkout('rounding',DemoPaymentMethod.Card,0,'');const queries=new DemoQueries(repo.snapshot());
    const a=queries.byCategory([sale],first.category),b=queries.byCategory([sale],second.category);
    expect(Number.isSafeInteger(a)).toBeTrue();expect(Number.isSafeInteger(b)).toBeTrue();expect(a+b).toBe(sale.total);expect(sale.total).toBe(183);
  });
  it('rejects unknown product state and monetary overflow without creating a sale', () => {
    const repo=new LocalDemoRepository();const p=repo.snapshot().products[0];const before=repo.snapshot();
    expect(()=>repo.saveProduct({...p,status:DemoProductStatus.Unknown})).toThrow();expect(repo.snapshot()).toBe(before);
    repo.saveProduct({...p,price:Number.MAX_SAFE_INTEGER});repo.cart(p.id,2);const pending=repo.snapshot();
    expect(()=>repo.checkout('overflow',DemoPaymentMethod.Card,0,'')).toThrow();expect(repo.snapshot()).toBe(pending);
  });
});

import { DemoQueries } from './demo-queries';
import { DemoPaymentMethod, DemoProductStatus } from './demo-types';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';
describe('demo monetary read model', () => {
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

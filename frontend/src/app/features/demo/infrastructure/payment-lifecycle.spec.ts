import { LocalDemoRepository } from './local-demo-repository';
import { DemoPaymentMethod as Method, DemoPaymentStatus as Status, DemoRole, DemoMovementKind, DemoScenario } from '../domain/demo-types';
import { DemoPermissionError } from '../domain/demo-permissions';
import { DemoWireMapper } from './demo-wire-mapper';
import { DemoQueries } from '../domain/demo-queries';
describe('explicit simulated payment lifecycle',()=>{
 let repo:LocalDemoRepository;
 beforeEach(()=>{repo=new LocalDemoRepository();repo.cart('p1',1);repo.selectCustomer('c1');repo.discount(10);});
 for(const method of Method.values) {
  it(method.label+' commits exactly once and partitions cash',()=>{
   const before=repo.snapshot(),cash=repo.expectedCash(),total=repo.total();
   const attempt=repo.beginPayment('one',method,method.isCash?total:0,'fiction');
   if(!method.isCash){expect(attempt.status).toBe(Status.Pending);expect(repo.snapshot().sales).toBe(before.sales);expect(repo.snapshot().products).toBe(before.products);expect(repo.snapshot().movements).toBe(before.movements);expect(repo.snapshot().cart).toBe(before.cart);expect(()=>repo.cart('p1',2)).toThrow();expect(()=>repo.closeCash(cash,'')).toThrow();}
   const approved=repo.resolvePayment('one',Status.Approved);
   expect(approved.status).toBe(Status.Approved);expect(repo.snapshot().sales.length).toBe(before.sales.length+1);expect(repo.snapshot().products[0].stock).toBe(before.products[0].stock-1);expect(repo.expectedCash()).toBe(cash+(method.isCash?total:0));
   expect(repo.resolvePayment('one',Status.Approved)).toBe(approved);
   expect(repo.beginPayment('one',method,method.isCash?total:0,'fiction')).toBe(approved);
  });
 }
 for(const method of [Method.Card,Method.Transfer,Method.QR])for(const status of [Status.Rejected,Status.Cancelled]){
  it(method.label+' '+status.label+' preserves cart, customer and discount and retries',()=>{
   const before=repo.snapshot();repo.beginPayment('first',method,0,'');repo.resolvePayment('first',status);
   expect(repo.snapshot().cart).toBe(before.cart);expect(repo.snapshot().customerId).toBe(before.customerId);expect(repo.snapshot().discount).toBe(before.discount);expect(repo.snapshot().products).toBe(before.products);expect(repo.snapshot().sales).toBe(before.sales);expect(repo.snapshot().movements).toBe(before.movements);
   expect(repo.resolvePayment('first',Status.Approved).status).toBe(status);
   repo.beginPayment('retry',method,0,'');expect(repo.resolvePayment('retry',Status.Approved).saleId).toBeDefined();
  });
 }
 it('closed payment vocabularies and transitions neutralize unknown wire inputs',()=>{
  for(const value of [undefined,null,'attacker',{},1]){expect(DemoWireMapper.payment(value)).toBe(Method.Unknown);expect(DemoWireMapper.paymentStatus(value)).toBe(Status.Unknown);}
  for(const method of Method.values)expect(DemoWireMapper.payment(method.key)).toBe(method);
  for(const status of Status.values){expect(DemoWireMapper.paymentStatus(status.key)).toBe(status);for(const next of [...Status.values,Status.Unknown])expect(status.canResolveTo(next)).toBe(status===Status.Pending&&[Status.Approved,Status.Rejected,Status.Cancelled].includes(next));}
  const before=repo.snapshot();expect(()=>repo.beginPayment('bad',Method.Unknown,0,'')).toThrow();expect(repo.snapshot()).toBe(before);
  repo.beginPayment('one',Method.QR,0,'');const pending=repo.snapshot();expect(()=>repo.resolvePayment('one',Status.Unknown)).toThrow();expect(repo.snapshot()).toBe(pending);
 });
 it('rejects a reused command with different payload or cart without mutating',()=>{
  repo.beginPayment('one',Method.QR,0,'reference');const before=repo.snapshot();
  for(const command of [()=>repo.beginPayment('one',Method.Card,0,'reference'),()=>repo.beginPayment('one',Method.QR,1,'reference'),()=>repo.beginPayment('one',Method.QR,0,'other'),()=>repo.beginPayment('one',Method.QR,100,'reference',1)]){expect(command).toThrowError(/contenido diferente/);expect(repo.snapshot()).toBe(before);}
  repo.resolvePayment('one',Status.Cancelled);repo.cart('p2',1);const changed=repo.snapshot();expect(()=>repo.beginPayment('one',Method.QR,0,'reference')).toThrowError(/contenido diferente/);expect(repo.snapshot()).toBe(changed);
 });
 it('revalidates a discounted pending approval after a profile change and permits explicit cancellation',()=>{
  repo.beginPayment('one',Method.Card,0,'');repo.setRole(DemoRole.Cashier);const before=repo.snapshot();
  expect(()=>repo.resolvePayment('one',Status.Approved)).toThrowError(DemoPermissionError);expect(repo.snapshot()).toBe(before);
  repo.resolvePayment('one',Status.Cancelled);repo.clearCart();repo.cart('p1',1);repo.beginPayment('retry',Method.Card,0,'');expect(repo.resolvePayment('retry',Status.Approved).saleId).toBeDefined();
 });
 for(const decision of [Status.Rejected,Status.Cancelled])it(decision.label+' cannot replay after clear, customer change and discount with an empty cart',()=>{
  repo.beginPayment('sameId',Method.Transfer,0,'DEMO');repo.resolvePayment('sameId',decision);
  repo.clearCart();repo.selectCustomer('c2');repo.discount(15);
  const before=repo.snapshot();let publications=0;repo.subscribe(()=>publications++);
  expect(before.cart.length).toBe(0);expect(before.customerId).toBe('c2');expect(before.discount).toBe(15);
  expect(()=>repo.beginPayment('sameId',Method.Transfer,0,'DEMO')).toThrowError(/contenido diferente/);
  expect(repo.snapshot()).toBe(before);expect(publications).toBe(1);
 });
 it('approved replay allows only its consumed context and original payload without publishing',()=>{
  repo.beginPayment('approved',Method.Transfer,0,'DEMO');const approved=repo.resolvePayment('approved',Status.Approved);
  const consumed=repo.snapshot();expect(consumed.cart.length).toBe(0);expect(consumed.discount).toBe(0);expect(consumed.customerId).toBe('c1');
  let publications=0;repo.subscribe(()=>publications++);
  expect(repo.beginPayment('approved',Method.Transfer,0,'DEMO')).toBe(approved);expect(repo.snapshot()).toBe(consumed);
  for(const command of [()=>repo.beginPayment('approved',Method.Card,0,'DEMO'),()=>repo.beginPayment('approved',Method.Transfer,0,'different'),()=>repo.beginPayment('approved',Method.Transfer,1,'DEMO')]){expect(command).toThrowError(/contenido diferente/);expect(repo.snapshot()).toBe(consumed);}
  expect(publications).toBe(1);
  repo.selectCustomer('c2');const customer=repo.snapshot();expect(()=>repo.beginPayment('approved',Method.Transfer,0,'DEMO')).toThrowError(/contenido diferente/);expect(repo.snapshot()).toBe(customer);
  repo.selectCustomer('c1');repo.discount(15);const discount=repo.snapshot();expect(()=>repo.beginPayment('approved',Method.Transfer,0,'DEMO')).toThrowError(/contenido diferente/);expect(repo.snapshot()).toBe(discount);
 });
 it('mixed QR reports only the electronic balance and compensates only cash',()=>{
  repo.discount(0);const cash=repo.expectedCash(),stock=repo.snapshot().products[0].stock,total=repo.total();repo.beginPayment('mixed',Method.QR,600000,'DEMO',500000);expect(repo.expectedCash()).toBe(cash);
  const approved=repo.resolvePayment('mixed',Status.Approved),sale=repo.snapshot().sales[0],query=new DemoQueries(repo.snapshot());
  expect(query.byMethod([sale],Method.Cash)).toBe(500000);expect(query.byMethod([sale],Method.QR)).toBe(total-500000);expect(sale.received-sale.cashAmount).toBe(100000);
  repo.reverse(approved.saleId!,'Ficticio');expect(repo.expectedCash()).toBe(cash);expect(repo.snapshot().products[0].stock).toBe(stock);expect(()=>repo.reverse(approved.saleId!,'Otra')).toThrow();
 });
 it('insufficient cash reversal leaves sale, all stock, movements and sequence atomic',()=>{
  repo.discount(0);repo.beginPayment('cash',Method.Cash,repo.total(),'');const sale=repo.snapshot().sales[0];repo.moveCash(DemoMovementKind.Expense,repo.expectedCash(),'vaciar demo');
  const before=repo.snapshot();expect(()=>repo.reverse(sale.id,'Ficticio')).toThrowError(/no alcanza/);expect(repo.snapshot()).toBe(before);expect(repo.expectedCash()).toBe(0);
  repo.moveCash(DemoMovementKind.Income,sale.cashAmount,'reponer demo');repo.reverse(sale.id,'Ficticio');expect(repo.expectedCash()).toBe(0);expect(repo.snapshot().sales[0].status.canReverse).toBeFalse();
 });
 it('invalid mixed amounts have no side effects and simulated failure stays retryable',()=>{
  const before=repo.snapshot();for(const [received,cash] of [[0,1],[0,-1],[repo.total(),repo.total()],[1,1.5]]){expect(()=>repo.beginPayment('bad',Method.QR,received,'',cash)).toThrow();expect(repo.snapshot()).toBe(before);}
  repo.reset(DemoScenario.Error);repo.cart('p1',1);const failed=repo.snapshot();expect(()=>repo.beginPayment('retry',Method.Transfer,0,'')).toThrowError(/Reintentá/);expect(repo.snapshot()).toBe(failed);repo.beginPayment('retry',Method.Transfer,0,'');expect(repo.resolvePayment('retry',Status.Approved).saleId).toBeDefined();
 });
});

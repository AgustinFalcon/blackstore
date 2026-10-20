import { DemoAction, DemoPermissionDecision, DemoPermissionError, DemoPermissionPolicy } from './demo-permissions';
import { DemoMovementKind, DemoPaymentMethod, DemoRole, DemoScenario } from './demo-types';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';

describe('demo action authorization', () => {
  const shared = [DemoAction.Read, DemoAction.Export, DemoAction.Print, DemoAction.EditCart, DemoAction.SelectCustomer, DemoAction.Checkout, DemoAction.OpenCash, DemoAction.MoveCash, DemoAction.CloseCash, DemoAction.SaveCustomer, DemoAction.Reset, DemoAction.ChangeRole];
  const manager = [DemoAction.Discount, DemoAction.Reverse, DemoAction.SaveProduct, DemoAction.AdjustStock];
  for (const action of shared) it(`allows both known profiles to ${action.label}`, () => {
    for (const role of DemoRole.values) expect(DemoPermissionPolicy.decide(role, action)).toBe(DemoPermissionDecision.Allowed);
  });
  for (const action of manager) it(`restricts ${action.label} to manager`, () => {
    expect(DemoPermissionPolicy.decide(DemoRole.Manager, action)).toBe(DemoPermissionDecision.Allowed);
    expect(DemoPermissionPolicy.decide(DemoRole.Cashier, action)).toBe(DemoPermissionDecision.Denied);
  });
  it('covers every action and denies either unknown input', () => {
    expect(new Set([...shared, ...manager]).size).toBe(DemoAction.values.length);
    for (const action of [...DemoAction.values, DemoAction.Unknown]) expect(DemoPermissionPolicy.decide(DemoRole.Unknown, action)).toBe(DemoPermissionDecision.Unknown);
    for (const role of DemoRole.values) expect(DemoPermissionPolicy.decide(role, DemoAction.Unknown)).toBe(DemoPermissionDecision.Unknown);
  });
  it('denies all unknown-profile mutations without publishing or allocating transaction IDs', () => {
    const repo = new LocalDemoRepository(undefined, DemoRole.Unknown);
    const before = repo.snapshot(); let publications = 0; repo.subscribe(() => publications++);
    const p = before.products[0], c = before.customers[1];
    const commands = [() => repo.setRole(DemoRole.Unknown), () => repo.cart(p.id, 1), () => repo.clearCart(), () => repo.selectCustomer(c.id), () => repo.discount(5), () => repo.checkout('denied', DemoPaymentMethod.Card, 0, ''), () => repo.reverse(before.sales[0].id, 'demo'), () => repo.saveProduct(p), () => repo.saveCustomer(c), () => repo.adjust(p.id, 1, 'demo'), () => repo.openCash(100), () => repo.moveCash(DemoMovementKind.Income, 100, 'demo'), () => repo.closeCash(100, 'demo'), () => repo.reset(DemoScenario.Normal)];
    for (const command of commands) { expect(command).toThrowError(DemoPermissionError); expect(repo.snapshot()).toBe(before); }
    expect(publications).toBe(1);
    repo.setRole(DemoRole.Manager); repo.cart(p.id, 1);
    expect(repo.checkout('denied', DemoPaymentMethod.Card, 0, '').id).toBe('V-101');
  });
  it('revalidates manager permissions immediately before executing open forms', () => {
    const repo = new LocalDemoRepository(); const product = repo.snapshot().products[0]; const sale = repo.snapshot().sales[0];
    const pending = [() => repo.adjust(product.id, 1, 'demo'), () => repo.saveProduct({...product, name: 'Edited'}), () => repo.reverse(sale.id, 'demo')];
    repo.setRole(DemoRole.Cashier); const before = repo.snapshot();
    for (const confirm of pending) { expect(confirm).toThrowError(DemoPermissionError); expect(repo.snapshot()).toBe(before); }
  });
  it('retains a pending discount and blocks cashier checkout until an explicit recovery', () => {
    const repo = new LocalDemoRepository(); repo.cart('p1', 1); repo.discount(10); repo.setRole(DemoRole.Cashier);
    const before = repo.snapshot(); expect(() => repo.checkout('pending', DemoPaymentMethod.Card, 0, '')).toThrowError(DemoPermissionError); expect(repo.snapshot()).toBe(before);
    expect(() => repo.discount(0)).toThrowError(DemoPermissionError);
    repo.clearCart(); repo.cart('p1', 1); expect(repo.checkout('pending', DemoPaymentMethod.Card, 0, '').discount).toBe(0);
  });
  it('allows cashier cash movements, close and reopen under the demo policy', () => {
    const repo = new LocalDemoRepository(); repo.setRole(DemoRole.Cashier); repo.moveCash(DemoMovementKind.Expense, 100, 'Bolsas'); repo.moveCash(DemoMovementKind.Income, 200, 'Fondo'); repo.closeCash(repo.expectedCash(), ''); repo.openCash(300); expect(repo.expectedCash()).toBe(300);
  });
});

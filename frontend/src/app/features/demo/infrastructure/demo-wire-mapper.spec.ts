import { DemoWireMapper } from './demo-wire-mapper';
import { DemoProductStatus, DemoCashStatus, DemoMovementKind, DemoPaymentMethod, DemoRole, DemoSaleStatus, DemoScenario, DemoViewState } from '../domain/demo-types';
import { DemoAction, DemoPermissionDecision } from '../domain/demo-permissions';
import { DemoPage } from '../domain/demo-page';
describe('single demo wire boundary', () => {
  it('maps all recognized external cases to canonical instances', () => {
    for (const value of DemoProductStatus.values) expect(DemoWireMapper.productStatus(value.key)).toBe(value);
    expect(DemoProductStatus.Active.toggled).toBe(DemoProductStatus.Inactive); expect(DemoProductStatus.Inactive.toggled).toBe(DemoProductStatus.Active); expect(DemoProductStatus.Unknown.toggled).toBe(DemoProductStatus.Unknown);
    for (const value of DemoPaymentMethod.values) expect(DemoWireMapper.payment(value.key)).toBe(value);
    for (const value of DemoRole.values) expect(DemoWireMapper.role(value.key)).toBe(value);
    for (const value of DemoSaleStatus.values) expect(DemoWireMapper.saleStatus(value.key)).toBe(value);
    for (const value of DemoScenario.values) expect(DemoWireMapper.scenario(value.key)).toBe(value);
    for (const value of DemoMovementKind.values) expect(DemoWireMapper.movement(value.key)).toBe(value);
    for (const value of DemoAction.values) expect(DemoWireMapper.action(value.key)).toBe(value);
    for (const value of DemoPage.values) expect(DemoWireMapper.page(value.key)).toBe(value);
    expect(DemoWireMapper.cashStatus('open')).toBe(DemoCashStatus.Open); expect(DemoWireMapper.cashStatus('closed')).toBe(DemoCashStatus.Closed);
    expect(DemoWireMapper.view('ready')).toBe(DemoViewState.Ready); expect(DemoWireMapper.view('loading')).toBe(DemoViewState.Loading);
    expect(DemoWireMapper.decision('allowed')).toBe(DemoPermissionDecision.Allowed); expect(DemoWireMapper.decision('denied')).toBe(DemoPermissionDecision.Denied);
  });
  it('fails closed for unknown, null and malformed values without exposing the raw label', () => {
    for (const raw of ['<script>evil</script>', null, undefined, {}, 1]) {
      expect(DemoWireMapper.productStatus(raw)).toBe(DemoProductStatus.Unknown); expect(DemoWireMapper.payment(raw)).toBe(DemoPaymentMethod.Unknown); expect(DemoWireMapper.role(raw)).toBe(DemoRole.Unknown); expect(DemoWireMapper.saleStatus(raw)).toBe(DemoSaleStatus.Unknown); expect(DemoWireMapper.scenario(raw)).toBe(DemoScenario.Unknown); expect(DemoWireMapper.movement(raw)).toBe(DemoMovementKind.Unknown); expect(DemoWireMapper.action(raw)).toBe(DemoAction.Unknown); expect(DemoWireMapper.page(raw)).toBe(DemoPage.Unknown); expect(DemoWireMapper.cashStatus(raw)).toBe(DemoCashStatus.Unknown); expect(DemoWireMapper.view(raw)).toBe(DemoViewState.Unknown); expect(DemoWireMapper.decision(raw)).toBe(DemoPermissionDecision.Unknown);
    }
  });
});

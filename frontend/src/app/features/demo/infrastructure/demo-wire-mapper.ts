import { DemoProductStatus, DemoCashStatus, DemoMovementKind, DemoPaymentMethod, DemoRole, DemoSaleStatus, DemoScenario, DemoViewState } from '../domain/demo-types';
import { DemoAction, DemoPermissionDecision } from '../domain/demo-permissions';
import { DemoPage } from '../domain/demo-page';
import { DemoDateRange } from '../domain/demo-calendar';
/** The only external input translator. Domain objects never retain unknown wire values. */
export class DemoWireMapper {
  static period(from: unknown, to: unknown) { return DemoDateRange.fromWire(from, to); }
  static productStatus(value: unknown) { return DemoProductStatus.fromWire(value); }
  static payment(value: unknown) { return DemoPaymentMethod.fromWire(value); }
  static cashStatus(value: unknown) { return DemoCashStatus.fromWire(value); }
  static saleStatus(value: unknown) { return DemoSaleStatus.fromWire(value); }
  static role(value: unknown) { return DemoRole.fromWire(value); }
  static scenario(value: unknown) { return DemoScenario.fromWire(value); }
  static movement(value: unknown) { return DemoMovementKind.fromWire(value); }
  static view(value: unknown) { return DemoViewState.fromWire(value); }
  static action(value: unknown) { return DemoAction.fromWire(value); }
  static decision(value: unknown) { return DemoPermissionDecision.fromWire(value); }
  static page(value: unknown) { return DemoPage.fromWire(value); }
}

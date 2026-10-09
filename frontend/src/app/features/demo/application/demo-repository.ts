import { DemoSnapshot, DemoPaymentMethod, DemoScenario, DemoRole, Product, Customer, Sale, DemoMovementKind } from '../domain/demo-types';
export abstract class DemoRepository {
  abstract snapshot(): DemoSnapshot;
  abstract subscribe(listener: (snapshot: DemoSnapshot) => void): () => void;
  abstract reset(scenario: DemoScenario): void;
  abstract setRole(role: DemoRole): void;
  abstract cart(productId: string, quantity: number): void;
  abstract clearCart(): void;
  abstract selectCustomer(id: string): void;
  abstract discount(percent: number): void;
  abstract total(): number;
  abstract checkout(commandId: string, method: DemoPaymentMethod, received: number, reference: string): Sale;
  abstract reverse(id: string, reason: string): void;
  abstract saveProduct(product: Product): void;
  abstract saveCustomer(customer: Customer): void;
  abstract adjust(productId: string, quantity: number, reason: string): void;
  abstract openCash(amount: number): void;
  abstract moveCash(kind: DemoMovementKind, amount: number, reason: string): void;
  abstract closeCash(declared: number, reason: string): void;
  abstract expectedCash(): number;
}

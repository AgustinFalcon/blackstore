import { DemoSnapshot, DemoPaymentAttempt, DemoPaymentStatus, DemoPaymentMethod, DemoScenario, DemoRole, Product, Customer, Sale, DemoMovementKind } from '../domain/demo-types';
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
  abstract beginPayment(commandId: string, method: DemoPaymentMethod, received: number, reference: string, cashAmount?: number): DemoPaymentAttempt;
  abstract resolvePayment(commandId: string, decision: DemoPaymentStatus): DemoPaymentAttempt;
  abstract reverse(id: string, reason: string): void;
  abstract saveProduct(product: Product): void;
  abstract saveCustomer(customer: Customer): void;
  abstract adjust(productId: string, quantity: number, reason: string): void;
  abstract openCash(amount: number): void;
  abstract moveCash(kind: DemoMovementKind, amount: number, reason: string): void;
  abstract closeCash(declared: number, reason: string): void;
  abstract expectedCash(): number;
}

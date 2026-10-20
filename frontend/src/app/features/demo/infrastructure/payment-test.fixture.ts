import { LocalDemoRepository } from './local-demo-repository';
import { DemoPaymentMethod, DemoPaymentStatus, Sale } from '../domain/demo-types';
/** Explicit terminal approval for existing repository/report regression fixtures. */
export function completePayment(repo: LocalDemoRepository, id: string, method: DemoPaymentMethod, received: number, reference: string): Sale {
  const started=repo.beginPayment(id,method,received,reference);
  const result=started.status.pending?repo.resolvePayment(id,DemoPaymentStatus.Approved):started;
  return repo.snapshot().sales.find(s=>s.id===result.saleId)!;
}

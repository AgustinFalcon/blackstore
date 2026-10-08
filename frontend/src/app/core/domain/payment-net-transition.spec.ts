import { PaymentLedgerSemantics,PaymentCoverage,TicketTransitionPolicy,TicketMoney,TicketSnapshot } from './ticket-transition';
import { SaleAction,SaleStatus } from './pos-types';
describe('v2 net payment transitions',()=>{
  const snapshot:TicketSnapshot={identity:{clientInstanceId:'c',deviceId:'d',saleId:'s',operationId:'o'},status:SaleStatus.PaymentCaptured,evidenceValid:true,receipt:'r',reservationRef:'r',
    total:TicketMoney.fromDecimal('18'),pending:TicketMoney.fromDecimal('18'),coverage:PaymentCoverage.Unpaid,hasPaymentHistory:true,blocked:false,retired:false,ledgerSemantics:PaymentLedgerSemantics.Net};
  it('permits release after authoritative net-zero refunds without rewriting history',()=>{
    expect(TicketTransitionPolicy.decide(snapshot,SaleAction.Release).permitsWrite).toBeTrue();
    expect(snapshot.hasPaymentHistory).toBeTrue();
  });
  it('does not extend legacy or unknown semantics to refunded history',()=>{
    for(const ledgerSemantics of [PaymentLedgerSemantics.Legacy,PaymentLedgerSemantics.Unknown]){
      expect(TicketTransitionPolicy.decide({...snapshot,ledgerSemantics},SaleAction.Release).permitsWrite).toBeFalse();
    }
  });
  it('positive remaining captured money cannot release even with Net semantics',()=>{
    expect(TicketTransitionPolicy.decide({...snapshot,coverage:PaymentCoverage.Partial,pending:TicketMoney.fromDecimal('8')},SaleAction.Release).permitsWrite).toBeFalse();
  });
});

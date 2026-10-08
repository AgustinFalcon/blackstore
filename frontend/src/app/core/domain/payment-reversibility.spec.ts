import { PaymentReversibility } from './durable-sale';
import { PaymentStatus,PaymentMethod } from './pos-types';
import { TicketMoney } from './ticket-transition';
describe('capture refund identity policy',()=>{
  const capture=(id:number)=>({paymentId:id,status:PaymentStatus.Captured,method:PaymentMethod.Cash,originalPaymentId:null,amount:TicketMoney.fromDecimal('10')});
  it('preserves a remaining split capture and blocks the refunded capture',()=>{
    const a=capture(1),b=capture(2),r={...a,paymentId:3,status:PaymentStatus.Refunded,originalPaymentId:1};
    expect(PaymentReversibility.forPayment(a,[a,b,r])).toBe(PaymentReversibility.AlreadyRefunded);
    expect(PaymentReversibility.forPayment(b,[a,b,r])).toBe(PaymentReversibility.Reversible);
  });
  it('rejects missing cross-sale self refund-to-refund duplicate reference method and amount mismatches',()=>{
    const a=capture(1),r={...a,paymentId:2,status:PaymentStatus.Refunded,originalPaymentId:1};
    for(const changed of [{originalPaymentId:null},{originalPaymentId:99},{originalPaymentId:2},{method:PaymentMethod.Card},{amount:TicketMoney.fromDecimal('9')}])
      expect(PaymentReversibility.validLedger([a,{...r,...changed}])).toBeFalse();
    expect(PaymentReversibility.validLedger([a,r,{...r,paymentId:3}])).toBeFalse();
    expect(PaymentReversibility.validLedger([a,r,{...r,paymentId:3,originalPaymentId:2}])).toBeFalse();
    expect(PaymentReversibility.validLedger([a,{...r,paymentId:1}])).toBeFalse();
  });
});

import { SaleAdmissionResolution,SaleAdmissionOutcome,SaleCommand,SaleCommandKind,SaleCommandFailure,SaleLineFailureCode } from './sale-command';
describe('terminal reservation rejection policy',()=>{
  const rejectedCommand=()=>SaleCommand.createMultilineReserve({clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'sale',operationId:'33333333-3333-3333-3333-333333333333'},2,[{variantId:'a',quantity:1,sku:'SKU',productName:'Artículo',expectedPriceVersion:'P1',originalUnitPrice:'1.00',discountAmount:'0.00'}]);
  const rejection=()=>({outcome:SaleAdmissionOutcome.Rejected,receipt:null,failure:SaleCommandFailure.Validation,lineFailures:[{variantId:'a',code:SaleLineFailureCode.PriceVersionMismatch}]});

  it('resolves only known failures on exact frozen variants and keeps labels on the type',()=>{
    expect(SaleAdmissionResolution.fromAdmission(rejectedCommand(),rejection())).toBe(SaleAdmissionResolution.TerminalLineRejection);
    expect(SaleLineFailureCode.PriceMismatch.label).toBeTruthy();
  });
  it('fails closed for uncertainty, authority failures, empty, foreign, duplicate and future line errors',()=>{
    const good=rejection();
    for(const invalid of [{...good,outcome:SaleAdmissionOutcome.Unknown},... [SaleCommandFailure.Forbidden,SaleCommandFailure.NotVisible,SaleCommandFailure.Unavailable].map(failure=>({...good,failure})),
      {...good,lineFailures:[]},{...good,lineFailures:[{variantId:'foreign',code:SaleLineFailureCode.PriceMismatch}]},
      {...good,lineFailures:[...good.lineFailures,...good.lineFailures]},{...good,lineFailures:[{variantId:'a',code:SaleLineFailureCode.Unknown}]}])
      expect(SaleAdmissionResolution.fromAdmission(rejectedCommand(),invalid)).toBe(SaleAdmissionResolution.Unknown);
    const old=SaleCommand.create(SaleCommandKind.Reserve,rejectedCommand().identity,2,{});
    expect(SaleAdmissionResolution.fromAdmission(old,good)).toBe(SaleAdmissionResolution.Unknown);
  });
});

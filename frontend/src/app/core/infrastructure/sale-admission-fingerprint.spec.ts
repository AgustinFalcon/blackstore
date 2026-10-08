import {SaleAdmissionFingerprint} from './sale-admission-fingerprint';
import {SaleCommand,SaleCommandKind,SaleAdmissionOutcome,SaleAdmissionVerification} from '../domain/sale-command';
import {PosWireMapper} from './pos-wire-mapper';
describe('sale-admission-v1 backend canonical fingerprint',()=>{
  const identity={clientInstanceId:'11111111-1111-1111-1111-111111111111',deviceId:'verified-device',saleId:'22222222-2222-2222-2222-222222222222',operationId:'33333333-3333-3333-3333-333333333333'};
  const fields={cashSessionId:2,variantId:'variant-1',quantity:1,expectedPriceVersion:'price-v1',sku:'SKU-1',productName:' Café ',originalUnitPrice:'20.000',discountAmount:'2',reason:' devolución '};
  it('matches the independent UTF-8 length-prefix SHA256 golden vector',async()=>{
    const command=SaleCommand.create(SaleCommandKind.Reserve,identity,2,fields);
    expect(await SaleAdmissionFingerprint.hash(command,7)).toBe('847af667a4807e0fee39a697a225244de8f2969a0a89ab4d64cda9e1ee0f49f9');
    expect(SaleAdmissionFingerprint.canonical(command,7)).toContain('5:Café');
  });
  it('normalizes backend semantic money and excludes commandId from the fingerprint',async()=>{
    const a=SaleCommand.create(SaleCommandKind.Reserve,identity,2,fields),b=SaleCommand.create(SaleCommandKind.Reserve,identity,2,{...fields,originalUnitPrice:'20.00',discountAmount:'2.00',productName:'Café'});
    expect(a.commandId).not.toBe(b.commandId);expect(await SaleAdmissionFingerprint.hash(a,7)).toBe(await SaleAdmissionFingerprint.hash(b,7));
  });
  it('actor, kind, amount and reason changes alter the expected digest',async()=>{
    const original=SaleCommand.create(SaleCommandKind.Reserve,identity,2,fields),hash=await SaleAdmissionFingerprint.hash(original,7);
    expect(await SaleAdmissionFingerprint.hash(original,8)).not.toBe(hash);
    for(const command of [SaleCommand.create(SaleCommandKind.Commit,identity,2,{reason:fields.reason}),SaleCommand.create(SaleCommandKind.Reserve,identity,2,{...fields,originalUnitPrice:'21'}),SaleCommand.create(SaleCommandKind.Reserve,identity,2,{...fields,reason:'otro'})])
      expect(await SaleAdmissionFingerprint.hash(command,7)).not.toBe(hash);
  });
  it('a syntactically valid foreign hash is PayloadMismatch and cannot resolve',async()=>{
    const command=SaleCommand.create(SaleCommandKind.Reserve,identity,2,fields),hash=await SaleAdmissionFingerprint.hash(command,7);
    const raw={code:202,traceId:'test',data:{outcome:SaleAdmissionOutcome.Accepted.wire,failure:null,receipt:{...identity,kind:command.kind.wire,commandId:command.commandId,actorId:7,cashSessionId:2,payloadHash:'f'.repeat(64),intentId:1,outboxId:2,acceptedAt:'2026-10-08T00:00:00Z'}}};
    const result=PosWireMapper.saleAdmission(raw,command,7,hash);
    expect(result.verification).toBe(SaleAdmissionVerification.PayloadMismatch);expect(result.receipt).toBeNull();expect(result.outcome).toBe(SaleAdmissionOutcome.Unknown);
  });
});

import {SaleAdmissionFingerprint,SaleAdmissionV2Fingerprint} from './sale-admission-fingerprint';
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
describe('sale-admission-v2 backend parity',()=>{
  const identity={clientInstanceId:'00000000-0000-0000-0000-000000000001',deviceId:'device',saleId:'sale',operationId:'00000000-0000-0000-0000-000000000002'};
  const lines=[{variantId:'a',quantity:2,expectedPriceVersion:'P1',sku:'SKU-A',productName:'Artículo A',originalUnitPrice:'10.00',discountAmount:'0.00'},
    {variantId:'b',quantity:1,expectedPriceVersion:'P2',sku:'SKU-B',productName:'Artículo B',originalUnitPrice:'7.50',discountAmount:'0.00'}];
  it('matches the independent Kotlin canonical and SHA-256 golden',async()=>{
    const codec=new SaleAdmissionV2Fingerprint(),command=SaleCommand.createMultilineReserve(identity,1,lines);
    expect(codec.canonical(command,7)).toBe('17:sale-admission-v27:Reserve1:71:136:00000000-0000-0000-0000-0000000000016:device4:sale36:00000000-0000-0000-0000-0000000000020:1:21:a1:22:P15:SKU-A11:Artículo A5:10.004:0.001:b1:12:P25:SKU-B11:Artículo B4:7.504:0.00');
    expect(await codec.hash(command,7)).toBe('fbfb0ce9bb08724799a906d71bd218e1c4112fab0c25f7c7b2ef04299b422cab');
  });
  it('sorts a copy by unsigned UTF-8 bytes and preserves evidence order',async()=>{
    const codec=new SaleAdmissionV2Fingerprint(),command=SaleCommand.createMultilineReserve(identity,1,[{...lines[0],variantId:'😀'},{...lines[1],variantId:'\ue000'}]);
    const canonical=codec.canonical(command,7);expect(canonical.indexOf('3:\ue000')).toBeLessThan(canonical.indexOf('4:😀'));
    expect((command.body['lines'] as readonly {variantId:string}[]).map(line=>line.variantId)).toEqual(['😀','\ue000']);
  });
  it('keeps backend V1 Reserve, Commit and Release goldens unchanged',async()=>{
    const multiline=SaleCommand.createMultilineReserve(identity,1,lines),first=(multiline.body['lines'] as readonly Record<string,unknown>[])[0];
    const reserve=SaleCommand.create(SaleCommandKind.Reserve,identity,1,{cashSessionId:1,...first});
    const commit=SaleCommand.create(SaleCommandKind.Commit,identity,1,{}),release=SaleCommand.create(SaleCommandKind.Release,identity,1,{});
    expect(await SaleAdmissionFingerprint.hash(reserve,7)).toBe('aa01960c783d27c817903495a4803b3fc803ef0621642c1f964c53eac8356722');
    expect(await SaleAdmissionFingerprint.hash(commit,7)).toBe('b1b70111889fe9c110a60c8ceec5fa300b7f4a5680d29a73c45f1fe91665b9bc');
    expect(await SaleAdmissionFingerprint.hash(release,7)).toBe('e8f8a2f79c5fac28fbe45553f32d6f4e97b63e4838dc8e579c2f5f313f17f735');
  });
});

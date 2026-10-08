import { SaleCommand,SaleCommandKind,SaleFingerprintVersion } from '../domain/sale-command';
import { TicketMoney } from '../domain/ticket-transition';

/** Exact backend SaleCommandFingerprint: sale-admission-v1, UTF-8 byte length prefixes, SHA-256. */
export class SaleAdmissionFingerprint {
  static readonly version=SaleFingerprintVersion.AdmissionV1;
  static canonical(command:SaleCommand,actorId:number):string {
    if(!Number.isSafeInteger(actorId)||actorId<1)throw new Error('Actor no comprobado');
    const q=command.identity,b=command.body;
    const fields=[this.version.wire,command.kind.wire,String(actorId),String(command.cashSessionId),q.clientInstanceId,q.deviceId,q.saleId,q.operationId,
      typeof b['reason']==='string'?b['reason'].trim():''];
    if(command.kind===SaleCommandKind.Reserve){
      const price=TicketMoney.fromDecimal(b['originalUnitPrice']),discount=TicketMoney.fromDecimal(b['discountAmount']??0);
      if(!price||!discount||typeof b['productName']!=='string')throw new Error('Payload no comprobado');
      fields.push(String(b['cashSessionId']),String(b['variantId']),String(b['quantity']),String(b['expectedPriceVersion']),String(b['sku']),
        b['productName'].trim(),price.decimal,discount.decimal);
    }
    const encoder=new TextEncoder();
    return fields.map(field=>`${encoder.encode(field).length}:${field}`).join('');
  }
  static async hash(command:SaleCommand,actorId:number):Promise<string>{
    const bytes=new TextEncoder().encode(this.canonical(command,actorId));
    const hash=await crypto.subtle.digest('SHA-256',bytes);
    return Array.from(new Uint8Array(hash),byte=>byte.toString(16).padStart(2,'0')).join('');
  }
}

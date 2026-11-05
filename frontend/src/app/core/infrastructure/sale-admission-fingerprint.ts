import { SaleCommand,SaleCommandKind,SaleFingerprintVersion } from '../domain/sale-command';
import { TicketMoney } from '../domain/ticket-transition';
import { SaleTextV2 } from '../domain/sale-text-v2';

export interface SaleFingerprintCodec {
  readonly version:SaleFingerprintVersion;
  canonical(command:SaleCommand,actorId:number):string;
  hash(command:SaleCommand,actorId:number):Promise<string>;
}

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

/** AdmissionV2 is a separate codec. V1 bytes and historical interpretation stay untouched. */
export class SaleAdmissionV2Fingerprint implements SaleFingerprintCodec {
  readonly version=SaleFingerprintVersion.AdmissionV2;
  canonical(command:SaleCommand,actorId:number):string {
    if(command.route.fingerprintVersion!==this.version||command.kind!==SaleCommandKind.Reserve||!Number.isSafeInteger(actorId)||actorId<1)
      throw new Error('Contrato V2 incompatible');
    const lines=command.body['lines'];
    if(!Array.isArray(lines)||lines.length<1||lines.length>100)throw new Error('Líneas inválidas');
    const reason=command.body['reason'];
    const fields=[this.version.wire,command.kind.wire,String(actorId),String(command.cashSessionId),
      command.identity.clientInstanceId,command.identity.deviceId,command.identity.saleId,command.identity.operationId,
      reason==null?'':SaleTextV2.reason(String(reason))!,String(lines.length)];
    const encoder=new TextEncoder();
    const sorted=[...lines].sort((left,right)=>this.compareUtf8(this.line(left).variantId,this.line(right).variantId));
    for(const raw of sorted){
      const line=this.line(raw),price=TicketMoney.fromDecimal(line.originalUnitPrice),discount=TicketMoney.fromDecimal(line.discountAmount);
      if(!price||!discount)throw new Error('Dinero inválido');
      fields.push(SaleTextV2.ref(line.variantId),String(line.quantity),SaleTextV2.ref(line.expectedPriceVersion),SaleTextV2.ref(line.sku),
        SaleTextV2.name(line.productName),price.decimal,discount.decimal);
    }
    return fields.map(field=>{SaleTextV2.validate(field);return `${encoder.encode(field).length}:${field}`;}).join('');
  }
  async hash(command:SaleCommand,actorId:number):Promise<string>{return digest(this.canonical(command,actorId));}
  private line(raw:unknown):{variantId:string;quantity:number;expectedPriceVersion:string;sku:string;productName:string;originalUnitPrice:string;discountAmount:string}{
    if(!raw||typeof raw!=='object'||Array.isArray(raw))throw new Error('Línea inválida');
    const line=raw as Record<string,unknown>,keys=['variantId','quantity','expectedPriceVersion','sku','productName','originalUnitPrice','discountAmount'];
    if(Object.keys(line).length!==keys.length||keys.some(key=>!(key in line))||!Number.isSafeInteger(line['quantity'])||(line['quantity'] as number)<1||
      keys.filter(key=>key!=='quantity').some(key=>typeof line[key]!=='string'))throw new Error('Línea inválida');
    return line as ReturnType<SaleAdmissionV2Fingerprint['line']>;
  }
  private compareUtf8(left:string,right:string):number {
    const a=new TextEncoder().encode(SaleTextV2.validate(left)),b=new TextEncoder().encode(SaleTextV2.validate(right));
    for(let index=0;index<Math.min(a.length,b.length);index+=1)if(a[index]!==b[index])return a[index]-b[index];
    return a.length-b.length;
  }
}

export class SaleFingerprintRegistry {
  private static readonly v1:SaleFingerprintCodec={version:SaleAdmissionFingerprint.version,
    canonical:(command,actorId)=>SaleAdmissionFingerprint.canonical(command,actorId),hash:(command,actorId)=>SaleAdmissionFingerprint.hash(command,actorId)};
  private static readonly v2=new SaleAdmissionV2Fingerprint();
  private constructor(){}
  static codec(command:SaleCommand):SaleFingerprintCodec {
    if(command.route.fingerprintVersion===SaleFingerprintVersion.AdmissionV1)return this.v1;
    if(command.route.fingerprintVersion===SaleFingerprintVersion.AdmissionV2)return this.v2;
    throw new Error('Versión de fingerprint desconocida');
  }
}

async function digest(canonical:string):Promise<string>{
  const hash=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(canonical));
  return Array.from(new Uint8Array(hash),byte=>byte.toString(16).padStart(2,'0')).join('');
}

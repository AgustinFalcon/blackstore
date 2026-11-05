/** Exact integer-cent arithmetic. Only checked values cross back into the UI. */
export class DemoMoney {
  private constructor() {}
  static integer(value: number): bigint {
    if (!Number.isSafeInteger(value) || value < 0) throw new Error('Ingresá un importe válido dentro del rango entero seguro, sin valores negativos.');
    return BigInt(value);
  }
  static number(value: bigint): number {
    if (value < 0n || value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('El importe supera el rango entero seguro.');
    return Number(value);
  }
  static decimal(cents: number): string {
    const value=this.integer(cents),pesos=value/100n,centavos=String(value%100n).padStart(2,'0');
    return `${pesos}.${centavos}`;
  }
  static sum(values: Iterable<number>): number {
    let total=0n;
    for (const value of values) total+=this.integer(value);
    return this.number(total);
  }
  static subtotal(lines: readonly {readonly price:number; readonly quantity:number}[]): number {
    return this.number(lines.reduce((sum,line)=>sum+this.integer(line.price)*this.integer(line.quantity),0n));
  }
  static discounted(lines: readonly {readonly price:number; readonly quantity:number}[], percent: number): number {
    if (!Number.isSafeInteger(percent) || percent<0 || percent>30) throw new Error('El descuento permitido es de 0 a 30 %.');
    return this.number((this.integer(this.subtotal(lines))*BigInt(100-percent)+50n)/100n);
  }
  static balance(movements: readonly {readonly amount:number; readonly kind:{readonly sign:number}}[]): number {
    return this.number(movements.reduce((sum,movement)=>sum+this.integer(movement.amount)*BigInt(movement.kind.sign),0n));
  }
}

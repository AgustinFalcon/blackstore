/** AdmissionV2 text policy. It deliberately does not use String.trim(). */
export class SaleTextV2 {
  private constructor() {}

  static validate(raw: string): string {
    for (let index = 0; index < raw.length; index += 1) {
      const unit = raw.charCodeAt(index);
      if (unit === 0) throw new Error('NUL no representable');
      if (unit >= 0xd800 && unit <= 0xdbff) {
        const next = raw.charCodeAt(index + 1);
        if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error('Secuencia Unicode inválida');
        index += 1;
      } else if (unit >= 0xdc00 && unit <= 0xdfff) {
        throw new Error('Secuencia Unicode inválida');
      }
    }
    return raw;
  }

  static trim(raw: string): string {
    this.validate(raw);
    let start = 0;
    let end = raw.length;
    while (start < end && this.edge(raw.charCodeAt(start))) start += 1;
    while (end > start && this.edge(raw.charCodeAt(end - 1))) end -= 1;
    return raw.slice(start, end);
  }

  static ref(raw: string): string {
    this.validate(raw);
    if (!raw || raw.length > 80 || raw !== this.trim(raw)) throw new Error('Referencia inválida');
    return raw;
  }

  static name(raw: string): string {
    const value = this.trim(raw);
    if (!value || value.length > 250) throw new Error('Nombre inválido');
    return value;
  }

  static reason(raw: string | null | undefined): string | null {
    if (raw == null) return null;
    const value = this.trim(raw);
    if (!value || value.length > 500) throw new Error('Motivo inválido');
    return value;
  }

  private static edge(unit: number): boolean {
    return unit === 0x20 || unit >= 0x09 && unit <= 0x0d;
  }
}

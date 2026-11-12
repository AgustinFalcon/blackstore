export class RecordedInstant {
  static readonly Unknown = new RecordedInstant(null);

  private constructor(private readonly value: Date | null) {}

  static fromWire(raw: unknown): RecordedInstant {
    if (typeof raw !== 'string' || raw.trim() !== raw || raw.length === 0) return this.Unknown;
    const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/.exec(raw);
    if (!parts) return this.Unknown;
    const epoch = Date.parse(raw);
    if (!Number.isFinite(epoch)) return this.Unknown;
    const value = new Date(epoch);
    const calendar = [value.getUTCFullYear(), value.getUTCMonth() + 1, value.getUTCDate(), value.getUTCHours(), value.getUTCMinutes(), value.getUTCSeconds()];
    const expected = parts.slice(1, 7).map(Number);
    return calendar.every((part, index) => part === expected[index]) ? new RecordedInstant(value) : this.Unknown;
  }

  get known(): boolean { return this.value !== null; }

  format(locale = 'es-AR'): string {
    if (this.value === null) return 'No disponible';
    return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short' }).format(this.value);
  }
}

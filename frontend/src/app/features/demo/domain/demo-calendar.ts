/** One business calendar for display, filtering, exports and fixture shifts. */
export class DemoCalendar {
  private constructor(private readonly timeZone: string) {}
  static readonly BuenosAires = new DemoCalendar('America/Argentina/Buenos_Aires');
  day(instant: string): string | null {
    const date = new Date(instant);
    if (!Number.isFinite(date.getTime())) return null;
    const parts = new Intl.DateTimeFormat('en-CA', {timeZone: this.timeZone, year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(date);
    const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(value => value.type === type)!.value;
    return `${part('year')}-${part('month')}-${part('day')}`;
  }
  sameDay(first: string, second: string): boolean { const day = this.day(first); return day !== null && day === this.day(second); }
  display(instant: string): string {
    const date = new Date(instant);
    return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('es-AR', {timeZone: this.timeZone, dateStyle: 'short', timeStyle: 'short'}).format(date) : 'Fecha no disponible';
  }
}

export class DemoDateRange {
  private constructor(private readonly from: string | null, private readonly to: string | null, private readonly valid = true) {}
  static readonly All = new DemoDateRange(null, null);
  static readonly Unknown = new DemoDateRange(null, null, false);
  static fromWire(from: unknown, to: unknown): DemoDateRange {
    const validDay = (value: unknown): value is string => {
      if (value === '') return true;
      if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
      const date = new Date(`${value}T00:00:00Z`);
      return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
    };
    if (!validDay(from) || !validDay(to) || from && to && from > to) return this.Unknown;
    return !from && !to ? this.All : new DemoDateRange(from || null, to || null);
  }
  includes(instant: string, calendar = DemoCalendar.BuenosAires): boolean {
    const day = calendar.day(instant);
    return this.valid && day !== null && (!this.from || day >= this.from) && (!this.to || day <= this.to);
  }
}

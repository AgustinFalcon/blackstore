/** Pure document-boundary selection, evaluated before either service graph exists. */
export class AppSurface {
  private constructor(readonly label: string) {}
  static readonly Demo = new AppSurface('Demostración');
  static readonly Operational = new AppSurface('POS');
  static readonly Unknown = new AppSurface('Superficie no disponible');
  static fromWire(pathname: unknown): AppSurface {
    if (typeof pathname !== 'string' || !pathname.startsWith('/')) return this.Unknown;
    try {
      const segments = pathname.split('/').filter(Boolean).map(segment => decodeURIComponent(segment));
      if (segments.some(segment => /[\\/?#]/.test(segment) || segment === '.' || segment === '..')) return this.Unknown;
      if (segments[0] === 'demo') return this.Demo;
      return segments.length <= 1 && ['', 'sesion', 'caja', 'catalogo', 'ticket', 'ventas', 'reportes'].includes(segments[0] ?? '')
        ? this.Operational : this.Unknown;
    } catch { return this.Unknown; }
  }
}

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
      const root = segments[0] ?? '';
      const direct = segments.length <= 1 && ['', 'sesion', 'caja', 'catalogo', 'ticket', 'ventas', 'reportes'].includes(root);
      const cashClose = segments.length === 2 && root === 'caja' && segments[1] === 'cierre';
      const ticketDetail = segments.length === 2 && root === 'ticket' && segments[1].length > 0;
      return direct || cashClose || ticketDetail ? this.Operational : this.Unknown;
    } catch { return this.Unknown; }
  }
}

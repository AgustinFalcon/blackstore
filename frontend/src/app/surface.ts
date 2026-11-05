/** Pure document-boundary selection, evaluated before either service graph exists. */
import { SaleRouteId } from './core/domain/sale-snapshot-state';

export class AppSurface {
  private constructor(readonly label: string) {}
  static readonly Demo = new AppSurface('Demostración');
  static readonly Operational = new AppSurface('POS');
  static readonly Unknown = new AppSurface('Superficie no disponible');
  static fromWire(pathname: unknown): AppSurface {
    if (typeof pathname !== 'string' || !pathname.startsWith('/')) return this.Unknown;
    try {
      const segments = pathname.split('/').filter(Boolean).map(segment => decodeURIComponent(segment));
      if (segments[0] === 'demo') return segments.some(segment => /[\\/?#]/.test(segment) || segment === '.' || segment === '..')
        ? this.Unknown : this.Demo;
      const root = segments[0] ?? '';
      const safeOperationalSegments = segments.every(segment => !/[\\/?#]/.test(segment) && segment !== '.' && segment !== '..');
      const direct = segments.length <= 1 && ['', 'sesion', 'caja', 'catalogo', 'ticket', 'ventas', 'reportes'].includes(root);
      const cashClose = safeOperationalSegments && segments.length === 2 && root === 'caja' && segments[1] === 'cierre';
      // The second segment is an opaque V2 reference. Encoded delimiters are data, not route structure.
      const ticketDetail = segments.length === 2 && root === 'ticket' && SaleRouteId.fromPathSegment(segments[1]) !== null;
      return safeOperationalSegments && direct || cashClose || ticketDetail ? this.Operational : this.Unknown;
    } catch { return this.Unknown; }
  }
}

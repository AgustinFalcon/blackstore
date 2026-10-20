import { AppSurface } from './surface';
describe('document surface boundary', () => {
  it('selects demo only for exact decoded segments', () => {
    for (const path of ['/demo', '/demo/', '/demo/ventas/V-101', '//demo//pos', '/%64emo/pos']) expect(AppSurface.fromWire(path)).toBe(AppSurface.Demo);
    for (const path of ['/demografia', '/demo%2fpos', '/demo/../sesion', '/demo/%zz', '/unknown', '/caja/x', null, 1]) expect(AppSurface.fromWire(path)).toBe(AppSurface.Unknown);
  });
  it('selects only known operational entry points', () => {
    for (const path of ['/', '/sesion', '/caja', '/catalogo', '/ticket', '/ventas', '/reportes']) expect(AppSurface.fromWire(path)).toBe(AppSurface.Operational);
  });
});

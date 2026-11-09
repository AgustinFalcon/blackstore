import { CatalogAcceptanceState, CatalogFreshness, CatalogSource, catalogAllowsSelection } from '../domain/catalog';
import { CatalogWireMapper } from './catalog-wire-mapper';

describe('CatalogWireMapper', () => {
  const valid = (source: unknown = 'LOCAL_FIXTURE', stale: unknown = false) => ({
    version: 'fixture-v1', source, stale, importedAt: null, validUntil: null, canonicalPath: '/blackstore-integration/v1',
    items: [{ sku: 'SKU-1', name: ' Producto ', variantId: 'variant-1', priceVersion: 'price-1', unitPrice: 10,
      rawSku: ' SKU-1 ', rawName: ' Producto ', rawVariantId: ' variant-1 ', rawPriceVersion: ' price-1 ' }],
  });

  it('maps the additive contract and keeps fixture use explicit', () => {
    const snapshot = CatalogWireMapper.snapshot(valid())!;
    expect(snapshot.source).toBe(CatalogSource.LocalFixture);
    expect(snapshot.freshness).toBe(CatalogFreshness.Current);
    expect(snapshot.items[0]).toEqual({sku:'SKU-1', name:'Producto', variantId:'variant-1', priceVersion:'price-1', unitPrice:10});
    expect(CatalogAcceptanceState.initial(snapshot)).toBe(CatalogAcceptanceState.Required);
    expect(catalogAllowsSelection(snapshot, CatalogAcceptanceState.Required)).toBeFalse();
    expect(catalogAllowsSelection(snapshot, CatalogAcceptanceState.Accepted)).toBeTrue();
  });

  it('fails closed for future source or freshness values', () => {
    const futureSource = CatalogWireMapper.snapshot(valid('FUTURE_SOURCE'))!;
    expect(futureSource.source).toBe(CatalogSource.Unknown);
    expect(catalogAllowsSelection(futureSource, CatalogAcceptanceState.Accepted)).toBeFalse();
    const futureFreshness = CatalogWireMapper.snapshot(valid('STORECORE_LIVE', 'future'))!;
    expect(futureFreshness.freshness).toBe(CatalogFreshness.Unknown);
    expect(catalogAllowsSelection(futureFreshness, CatalogAcceptanceState.NotRequired)).toBeFalse();
  });

  it('rejects malformed items instead of leaking raw wire', () => {
    expect(CatalogWireMapper.snapshot({...valid(), items:[{sku:'',name:'Producto',variantId:'1',unitPrice:10}]})).toBeNull();
    expect(CatalogWireMapper.snapshot({...valid(), items:[{sku:'1',name:'Producto',variantId:'1',unitPrice:-1}]})).toBeNull();
    expect(CatalogWireMapper.snapshot({...valid(), items:{}})).toBeNull();
  });

  it('preserves non-ASCII identity edges exactly like the sale V2 contract', () => {
    const edge = '\ufeff';
    const snapshot = CatalogWireMapper.snapshot({...valid(), items:[{
      sku:`${edge}SKU-1`, name:`${edge}Producto`, variantId:`${edge}variant-1`,
      priceVersion:`${edge}price-1`, unitPrice:10,
    }]})!;
    expect(snapshot.items[0]).toEqual({
      sku:`${edge}SKU-1`, name:`${edge}Producto`, variantId:`${edge}variant-1`,
      priceVersion:`${edge}price-1`, unitPrice:10,
    });
  });
});

import { catalogIsClosed, expectedDrawer, newSaleBlockReason } from './sale-block';

describe('newSaleBlockReason', () => {
  const openCatalog = {
    version: 'fixture-v1',
    stale: false,
    importedAt: null,
    validUntil: null,
    items: [{ sku: 'SKU-1', name: 'Cafe', variantId: 'variant-1', priceVersion: null, unitPrice: null }],
  };

  it('blocks a new sale when the local catalog is closed', () => {
    expect(catalogIsClosed(null, true)).toBeTrue();
    expect(
      newSaleBlockReason({
        loading: false,
        catalog: { ...openCatalog, stale: true },
        catalogUnavailable: false,
        hasOpenSession: true,
        stockBlocked: false,
        writesDisabled: false,
      }),
    ).toContain('No inicies una venta nueva');
  });

  it('keeps the new sale disabled when there is no sellable stock', () => {
    expect(
      newSaleBlockReason({
        loading: false,
        catalog: openCatalog,
        catalogUnavailable: false,
        hasOpenSession: true,
        stockBlocked: true,
        writesDisabled: false,
      }),
    ).toContain('Sin stock');
  });

  it('allows the sale when the catalog is open and the drawer is open', () => {
    expect(
      newSaleBlockReason({
        loading: false,
        catalog: openCatalog,
        catalogUnavailable: false,
        hasOpenSession: true,
        stockBlocked: false,
        writesDisabled: false,
      }),
    ).toBeNull();
  });
});

describe('expectedDrawer', () => {
  it('adds cash in and subtracts expenses from the opening float', () => {
    expect(expectedDrawer({ openingCash: 100, cashIn: 40, expenses: [{ amount: 15 }] })).toBe(125);
  });
});

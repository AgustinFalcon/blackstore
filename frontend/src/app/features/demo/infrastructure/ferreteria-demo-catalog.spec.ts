import { FERRETERIA_DEMO_PRODUCTS } from './ferreteria-demo-catalog';

describe('Ferretería demo catalogue', () => {
  it('provides the complete unique commercial pool', () => {
    expect(FERRETERIA_DEMO_PRODUCTS.length).toBe(80);
    expect(new Set(FERRETERIA_DEMO_PRODUCTS.map(product => product.sku)).size).toBe(80);
    expect(new Set(FERRETERIA_DEMO_PRODUCTS.map(product => product.category)).size).toBe(14);
  });

  it('keeps every product displayable and commercially valid', () => {
    for (const product of FERRETERIA_DEMO_PRODUCTS) {
      expect(product.name.trim()).not.toBe('');
      expect(product.imageUrl).toMatch(/^https:\/\/images\.unsplash\.com\//);
      expect(Number.isSafeInteger(product.price)).toBeTrue();
      expect(product.price).toBeGreaterThan(0);
      expect(Number.isSafeInteger(product.stock)).toBeTrue();
      expect(product.stock).toBeGreaterThanOrEqual(0);
      expect(product.art).not.toBeNull();
    }
  });

  it('includes normal, low and exhausted stock evidence', () => {
    expect(FERRETERIA_DEMO_PRODUCTS.some(product => product.stock === 0)).toBeTrue();
    expect(FERRETERIA_DEMO_PRODUCTS.some(product => product.stock > 0 && product.stock <= 3)).toBeTrue();
    expect(FERRETERIA_DEMO_PRODUCTS.some(product => product.stock >= 20)).toBeTrue();
  });
});


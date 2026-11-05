import { Product, SaleLine } from './demo-types';

/** Prepare every restored quantity before a reversal allocates events or commits. */
export class StockRestorationPlan {
  private constructor(readonly products: readonly Product[]) {}
  static prepare(products: readonly Product[], lines: readonly SaleLine[]): StockRestorationPlan {
    const quantities = new Map<string, number>();
    for (const line of lines) {
      const quantity = (quantities.get(line.productId) ?? 0) + line.quantity;
      if (!Number.isSafeInteger(line.quantity) || line.quantity <= 0 || !Number.isSafeInteger(quantity)) throw new Error('La devolución excede el rango entero seguro de stock.');
      quantities.set(line.productId, quantity);
    }
    const restored = new Map<string, number>();
    for (const [id, quantity] of quantities) {
      const product = products.find(item => item.id === id);
      const stock = product ? product.stock + quantity : NaN;
      if (!product || !Number.isSafeInteger(product.stock) || product.stock < 0 || !Number.isSafeInteger(stock) || stock < 0) throw new Error('La devolución excede el rango entero seguro de stock.');
      restored.set(id, stock);
    }
    return new StockRestorationPlan(products.map(product => restored.has(product.id) ? {...product, stock: restored.get(product.id)!} : product));
  }
}

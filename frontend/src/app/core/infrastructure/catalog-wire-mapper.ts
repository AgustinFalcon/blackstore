import { CatalogFreshness, CatalogItem, CatalogSnapshot, CatalogSource } from '../domain/catalog';
import { SaleTextV2 } from '../domain/sale-text-v2';
import { CatalogItemWire, CatalogSnapshotWire } from '../models/catalog-wire';

export class CatalogWireMapper {
  private constructor() {}

  static snapshot(raw: unknown): CatalogSnapshot | null {
    if (!this.record(raw)) return null;
    const wire = raw as CatalogSnapshotWire;
    if (!Array.isArray(wire.items)) return null;
    const items: CatalogItem[] = [];
    for (const item of wire.items) {
      const mapped = this.item(item);
      if (!mapped) return null;
      items.push(mapped);
    }
    const source = CatalogSource.fromWire(wire.source);
    return {
      version: this.nullableText(wire.version),
      source,
      freshness: CatalogFreshness.fromWire(wire.stale, source),
      importedAt: this.nullableText(wire.importedAt),
      validUntil: this.nullableText(wire.validUntil),
      canonicalPath: this.nullableText(wire.canonicalPath),
      items,
    };
  }

  private static item(raw: unknown): CatalogItem | null {
    if (!this.record(raw)) return null;
    const wire = raw as CatalogItemWire;
    const sku = this.requiredRef(wire.sku);
    const name = this.requiredName(wire.name);
    const variantId = this.requiredRef(wire.variantId);
    const priceVersion = this.nullableRef(wire.priceVersion);
    const unitPrice = wire.unitPrice === null || wire.unitPrice === undefined
      ? null
      : typeof wire.unitPrice === 'number' && Number.isFinite(wire.unitPrice) && wire.unitPrice >= 0
        ? wire.unitPrice
        : Number.NaN;
    if (!sku || !name || !variantId || Number.isNaN(unitPrice)) return null;
    return { sku, name, variantId, priceVersion, unitPrice };
  }

  private static record(raw: unknown): raw is Record<string, unknown> {
    return typeof raw === 'object' && raw !== null && !Array.isArray(raw);
  }

  private static requiredText(raw: unknown): string | null {
    return typeof raw === 'string' && raw.trim().length > 0 ? raw.trim() : null;
  }

  private static requiredRef(raw: unknown): string | null {
    try { return typeof raw === 'string' ? SaleTextV2.ref(raw) : null; }
    catch { return null; }
  }

  private static requiredName(raw: unknown): string | null {
    try { return typeof raw === 'string' ? SaleTextV2.name(raw) : null; }
    catch { return null; }
  }

  private static nullableRef(raw: unknown): string | null {
    if (raw === null || raw === undefined) return null;
    return this.requiredRef(raw);
  }

  private static nullableText(raw: unknown): string | null {
    if (raw === null || raw === undefined) return null;
    return this.requiredText(raw);
  }
}

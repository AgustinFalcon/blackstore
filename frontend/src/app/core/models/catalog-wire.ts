export interface CatalogItemWire {
  readonly sku?: unknown;
  readonly name?: unknown;
  readonly variantId?: unknown;
  readonly priceVersion?: unknown;
  readonly unitPrice?: unknown;
  readonly rawSku?: unknown;
  readonly rawName?: unknown;
  readonly rawVariantId?: unknown;
  readonly rawPriceVersion?: unknown;
}

export interface CatalogSnapshotWire {
  readonly version?: unknown;
  readonly source?: unknown;
  readonly stale?: unknown;
  readonly importedAt?: unknown;
  readonly validUntil?: unknown;
  readonly canonicalPath?: unknown;
  readonly items?: unknown;
}

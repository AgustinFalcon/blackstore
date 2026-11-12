CREATE TABLE public.catalog_snapshot_cache (
 singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
 catalog_version VARCHAR(160) NOT NULL CHECK (btrim(catalog_version) <> ''),
 imported_at TIMESTAMPTZ NOT NULL,
 valid_until TIMESTAMPTZ NOT NULL,
 canonical_path VARCHAR(160) NOT NULL CHECK (btrim(canonical_path) <> ''),
 contract_version VARCHAR(80) NOT NULL CHECK (btrim(contract_version) <> ''),
 contract_digest VARCHAR(128) NULL,
 stored_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK (valid_until IS NULL OR valid_until > imported_at)
);

CREATE TABLE public.catalog_snapshot_items (
 singleton BOOLEAN NOT NULL DEFAULT TRUE CHECK (singleton),
 projection_kind VARCHAR(16) NOT NULL CHECK (projection_kind IN ('LEGACY','V2')),
 ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
 sku VARCHAR(160) NOT NULL CHECK (btrim(sku) <> ''),
 product_name VARCHAR(250) NOT NULL CHECK (btrim(product_name) <> ''),
 variant_id VARCHAR(160) NOT NULL CHECK (btrim(variant_id) <> ''),
 price_version VARCHAR(160) NULL,
 unit_price NUMERIC(14,2) NULL CHECK (unit_price IS NULL OR unit_price >= 0),
 raw_sku VARCHAR(160) NOT NULL CHECK (raw_sku <> ''),
 raw_name VARCHAR(250) NOT NULL CHECK (raw_name <> ''),
 raw_variant_id VARCHAR(160) NOT NULL CHECK (raw_variant_id <> ''),
 raw_price_version VARCHAR(160) NULL,
 PRIMARY KEY (singleton, projection_kind, ordinal),
 UNIQUE (singleton, projection_kind, sku),
 UNIQUE (singleton, projection_kind, variant_id),
 FOREIGN KEY (singleton) REFERENCES public.catalog_snapshot_cache(singleton) ON DELETE CASCADE
);

REVOKE ALL ON public.catalog_snapshot_cache, public.catalog_snapshot_items FROM PUBLIC,
 blackstore_app, blackstore_projection_worker, blackstore_outbox_worker, blackstore_auditor;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.catalog_snapshot_cache, public.catalog_snapshot_items TO blackstore_app;
GRANT SELECT ON public.catalog_snapshot_cache, public.catalog_snapshot_items TO blackstore_auditor;

-- The sale projection trigger evaluates fiscal authorization for every terminal transition.
-- The runtime role therefore needs the same read capability as the projection worker.
GRANT SELECT ON fiscal_production_authorizations TO blackstore_app;

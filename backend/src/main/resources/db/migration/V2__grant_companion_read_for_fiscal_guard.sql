-- The fiscal guard reads companion_installation while the app inserts a sale projection
-- and while the projection worker updates it. SELECT is the minimum grant that lets that trigger run.
GRANT SELECT ON companion_installation TO blackstore_app, blackstore_projection_worker;

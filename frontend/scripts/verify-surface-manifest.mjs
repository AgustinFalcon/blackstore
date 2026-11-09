import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(frontendRoot, '..');
const manifestPath = resolve(repoRoot, 'sdd/wip/20261109-blackstore-productive-surface-data-parity/1-functional/surface-data-manifest.json');
const routesPath = resolve(frontendRoot, 'src/app/app.routes.ts');
const demoBootstrapPath = resolve(frontendRoot, 'src/app/demo.bootstrap.ts');

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const routesSource = readFileSync(routesPath, 'utf8');
const demoBootstrap = readFileSync(demoBootstrapPath, 'utf8');
const evidenceComponent = readFileSync(resolve(frontendRoot, 'src/app/shared/surface-data-evidence.component.ts'), 'utf8');
const productiveSurfaceSources = [
  'src/app/features/session/session.component.ts',
  'src/app/features/pos/shell/pos-shell.component.ts',
  'src/app/features/cash/cash-session.component.ts',
  'src/app/features/catalog/catalog-panel.component.ts',
  'src/app/features/sales/sale-ticket.component.ts',
  'src/app/features/sales/sale-ticket.component.html',
  'src/app/features/sales/sale-ticket-snapshot.component.ts',
  'src/app/features/sales/durable-sales.component.ts',
  'src/app/features/reports/shift-report.component.ts',
].map(path => readFileSync(resolve(frontendRoot, path), 'utf8')).join('\n');
const allowedSources = ['REAL', 'FIXTURE', 'MIXED', 'UNAVAILABLE', 'BLOCKED', 'UNKNOWN'];

function fail(message) {
  throw new Error(`[surface-manifest] ${message}`);
}

function aggregate(sources) {
  if (sources.includes('UNKNOWN')) return 'UNKNOWN';
  if (sources.includes('UNAVAILABLE')) return 'UNAVAILABLE';
  if (sources.includes('BLOCKED')) return 'BLOCKED';
  if (sources.every(source => source === 'REAL')) return 'REAL';
  if (sources.every(source => source === 'FIXTURE')) return 'FIXTURE';
  if (sources.every(source => ['REAL', 'FIXTURE', 'MIXED'].includes(source))) return 'MIXED';
  return 'UNKNOWN';
}

if (manifest.kind !== 'EXPECTED_CONTRACT') fail('kind must remain EXPECTED_CONTRACT');
if (JSON.stringify(manifest.sourceVocabulary) !== JSON.stringify(allowedSources)) fail('closed source vocabulary changed');
if (!Array.isArray(manifest.surfaces) || manifest.surfaces.length === 0) fail('surfaces must be non-empty');

const routeMatches = [...routesSource.matchAll(/\bpath:\s*'([^']*)'/g)].map(match => match[1]);
const operationalRoutes = routeMatches
  .filter(path => path !== '**')
  .map(path => path === '' ? '/' : `/${path}`);
const manifestOperationalRoutes = manifest.surfaces
  .filter(surface => surface.route !== '/demo/**')
  .map(surface => surface.route);

if (new Set(manifestOperationalRoutes).size !== manifestOperationalRoutes.length) fail('operational route duplicated');
for (const route of operationalRoutes) {
  if (!manifestOperationalRoutes.includes(route)) fail(`route ${route} is missing from manifest`);
}
for (const route of manifestOperationalRoutes) {
  if (!operationalRoutes.includes(route)) fail(`manifest route ${route} does not exist in app.routes.ts`);
}
if (!demoBootstrap.includes("path: 'demo'") && !demoBootstrap.includes("path:'demo'")) fail('demo bootstrap no longer mounts /demo');
if (!manifest.surfaces.some(surface => surface.route === '/demo/**')) fail('/demo/** is missing from manifest');
if (!evidenceComponent.includes('[attr.data-surface-id]') || !evidenceComponent.includes('[attr.data-source]') ||
    !evidenceComponent.includes('[attr.data-dataset]')) fail('runtime evidence attributes are incomplete');

for (const surface of manifest.surfaces) {
  if (!surface.id || !surface.route || !allowedSources.includes(surface.expectedSource)) fail(`invalid surface ${surface.id ?? '<unknown>'}`);
  if (!Array.isArray(surface.datasets) || surface.datasets.length === 0) fail(`${surface.id} has no required datasets`);
  const datasetIds = new Set();
  const sources = [];
  for (const dataset of surface.datasets) {
    if (!dataset.id || !dataset.owner || !dataset.persistence) fail(`${surface.id} has an incomplete dataset`);
    if (datasetIds.has(dataset.id)) fail(`${surface.id} duplicates dataset ${dataset.id}`);
    datasetIds.add(dataset.id);
    if (!allowedSources.includes(dataset.expectedSource)) fail(`${surface.id}/${dataset.id} has invalid source`);
    sources.push(dataset.expectedSource);
  }
  const computed = aggregate(sources);
  if (computed !== surface.expectedSource) fail(`${surface.id} expected ${surface.expectedSource}, computed ${computed}`);
  if (surface.route !== '/demo/**') {
    if (!productiveSurfaceSources.includes(surface.id)) fail(`${surface.id} has no runtime surface identity`);
    for (const dataset of surface.datasets) {
      if (!productiveSurfaceSources.includes(dataset.id)) fail(`${surface.id}/${dataset.id} has no runtime dataset identity`);
    }
  }
}

console.log(`[surface-manifest] PASS ${manifest.surfaces.length} surfaces, ${operationalRoutes.length} operational routes`);

import { AppSurface } from './app/surface';

const surface = AppSurface.fromWire(location.pathname);
const bootstrap = surface === AppSurface.Demo
  ? import('./app/demo.bootstrap').then(module => module.bootstrapDemo())
  : surface === AppSurface.Operational
    ? import('./app/operational.bootstrap').then(module => module.bootstrapOperational())
    : import('./app/unknown.bootstrap').then(module => module.bootstrapUnknown());
bootstrap.catch(error => console.error(error));

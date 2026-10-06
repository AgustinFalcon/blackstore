# BlackStore frontend

Angular 22 POS console skeleton (Clean Architecture layout: `core/`, `features/`, `shared/`).

## Requisitos

- Node.js **^22.22.3** (Angular 22) y TypeScript 6. `npm run build` genera el bundle.
- Backend local en `http://127.0.0.1:8081`; `npm start` usa el proxy dev para `/api/**` y conserva el origen del browser.
- Producción sirve frontend y `/api/v1` en el mismo origen mediante reverse proxy. No se configura una URL absoluta en el bundle.
- `/sesion` inicia sesión con cuentas staff provisionadas por la herramienta local del backend; no hay credencial por defecto.
- Cookie de sesión HttpOnly administrada por el backend. El token CSRF queda sólo en memoria y rota al autenticar; no se guarda identidad ni secreto en storage.

## Comandos (con Node 22+)

```bash
npm install
npm start
npm test
npm run typecheck
npm run build
```

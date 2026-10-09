import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
const root=resolve('dist/blackstore-frontend/browser');
createServer(async(req,res)=>{try{const url=new URL(req.url,'http://localhost');const path=url.pathname.startsWith('/demo')?'/index.html':url.pathname;const filename=resolve(root,'.'+path);if(!filename.startsWith(root+sep)){res.writeHead(404);res.end();return;}const bytes=await readFile(filename);const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'};res.setHeader('Content-Type',types[extname(filename)]??'application/octet-stream');res.end(bytes);}catch{res.writeHead(404);res.end();}}).listen(4215,'127.0.0.1');

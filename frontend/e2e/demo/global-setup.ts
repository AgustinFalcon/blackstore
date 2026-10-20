import { spawn } from 'node:child_process';
import { resolve } from 'node:path';

/** Own the direct Node child; teardown never uses an HTTP control endpoint. */
export default async function setup() {
  const child=spawn(process.execPath,[resolve('e2e/demo/server.mjs')],{stdio:['ignore','pipe','pipe'],windowsHide:true});
  await new Promise<void>((resolveReady,reject)=>{
    let error='',output='';const timer=setTimeout(()=>{child.kill();reject(new Error('Demo server startup timed out: '+error));},10000);
    child.stderr.on('data',chunk=>{error+=String(chunk);});
    child.once('error',cause=>{clearTimeout(timer);reject(cause);});
    child.once('exit',code=>{clearTimeout(timer);reject(new Error(`Demo server exited (${code}): ${error}`));});
    child.stdout.on('data',chunk=>{output+=String(chunk);if(output.includes('demo-server-ready')){clearTimeout(timer);resolveReady();}});
  });
  return async()=>{
    if(child.exitCode!==null||child.signalCode!==null)return;
    await new Promise<void>(resolveExit=>{child.once('exit',()=>resolveExit());child.kill();});
  };
}

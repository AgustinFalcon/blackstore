const fs=require('node:fs');const path=require('node:path');const ts=require('typescript');const jc=require('jasmine-core');
const jasmine=jc.core(jc);const env=jasmine.getEnv();Object.assign(global,jc.interface(jasmine,env));
const cache=new Map();
function load(filename){filename=path.resolve(filename);if(cache.has(filename))return cache.get(filename).exports;const module={exports:{}};cache.set(filename,module);const source=fs.readFileSync(filename,'utf8');const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;new Function('require','module','exports',code)(name=>name.startsWith('.')?load(path.resolve(path.dirname(filename),name+'.ts')):require(name),module,module.exports);return module.exports;}
let count=0,failed=0;env.addReporter({specDone(result){count++;if(result.status==='failed'){failed++;console.error(result.fullName,result.failedExpectations);}else console.log('PASS',result.description);},jasmineDone(){console.log(`${count} specs, ${failed} failed`);process.exitCode=failed?1:0;}});
load('src/app/features/demo/infrastructure/local-demo-repository.spec.ts');env.execute();

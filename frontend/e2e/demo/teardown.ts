export default async function teardown():Promise<void>{
 await fetch('http://127.0.0.1:4215/__demo_test_stop',{method:'POST'}).catch(()=>undefined);
}

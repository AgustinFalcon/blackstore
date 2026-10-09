import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./e2e/demo',timeout:30000,use:{baseURL:'http://127.0.0.1:4215',headless:true},webServer:{command:'node e2e/demo/server.mjs',url:'http://127.0.0.1:4215/demo',reuseExistingServer:false,timeout:30000},reporter:[['list'],['html',{outputFolder:'demo-test-report',open:'never'}]],outputDir:'demo-test-results'});

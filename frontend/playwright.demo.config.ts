import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./e2e/demo',timeout:30000,globalSetup:'./e2e/demo/global-setup.ts',use:{baseURL:'http://127.0.0.1:4215',headless:true},reporter:[['list'],['html',{outputFolder:'demo-test-report',open:'never'}]],outputDir:'demo-test-results'});

import {DOCUMENT} from '@angular/common';
import {TestBed} from '@angular/core/testing';
import {BrowserForegroundRefresh} from './foreground-refresh';

describe('foreground browser observation lifecycle',()=>{
  it('registers native listeners once, skips hidden, unsubscribes and cleans up',()=>{
    const window=new EventTarget(),document=Object.assign(new EventTarget(),{defaultView:window,visibilityState:'hidden'});
    const windowAdd=spyOn(window,'addEventListener').and.callThrough(),documentAdd=spyOn(document,'addEventListener').and.callThrough();
    const windowRemove=spyOn(window,'removeEventListener').and.callThrough(),documentRemove=spyOn(document,'removeEventListener').and.callThrough();
    TestBed.configureTestingModule({providers:[{provide:DOCUMENT,useValue:document}]});
    const port=TestBed.inject(BrowserForegroundRefresh),one=jasmine.createSpy(),two=jasmine.createSpy();
    const unsubscribe=port.subscribe(one);port.subscribe(two);expect(TestBed.inject(BrowserForegroundRefresh)).toBe(port);
    expect(windowAdd.calls.count()).toBe(1);expect(documentAdd.calls.count()).toBe(1);
    document.dispatchEvent(new Event('visibilitychange'));expect(one).not.toHaveBeenCalled();
    document.visibilityState='visible';document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('focus'));
    expect(one.calls.count()).toBe(2);expect(two.calls.count()).toBe(2);unsubscribe();window.dispatchEvent(new Event('focus'));
    expect(one.calls.count()).toBe(2);expect(two.calls.count()).toBe(3);
    TestBed.resetTestingModule();expect(windowRemove.calls.count()).toBe(1);expect(documentRemove.calls.count()).toBe(1);
    window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));expect(two.calls.count()).toBe(3);
  });
});

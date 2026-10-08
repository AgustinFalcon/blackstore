import {DOCUMENT} from '@angular/common';
import {DestroyRef,Injectable,InjectionToken,inject} from '@angular/core';

export abstract class ForegroundRefreshPort {
  abstract subscribe(listener:()=>void):()=>void;
}

/** Owns browser wake-up observations only; it carries no accounting authority. */
@Injectable({providedIn:'root'})
export class BrowserForegroundRefresh extends ForegroundRefreshPort {
  private readonly document=inject(DOCUMENT);
  private readonly listeners=new Set<()=>void>();
  constructor(){
    super();
    const window=this.document.defaultView;
    const focus=()=>this.emit();
    const visibility=()=>{if(this.document.visibilityState==='visible')this.emit();};
    window?.addEventListener('focus',focus);
    this.document.addEventListener('visibilitychange',visibility);
    inject(DestroyRef).onDestroy(()=>{
      window?.removeEventListener('focus',focus);
      this.document.removeEventListener('visibilitychange',visibility);
      this.listeners.clear();
    });
  }
  subscribe(listener:()=>void):()=>void{this.listeners.add(listener);return ()=>this.listeners.delete(listener);}
  private emit():void{for(const listener of this.listeners)listener();}
}
export const FOREGROUND_REFRESH=new InjectionToken<ForegroundRefreshPort>('Foreground refresh',{
  providedIn:'root',factory:()=>inject(BrowserForegroundRefresh),
});

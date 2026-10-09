import { AfterViewInit, Directive, ElementRef, HostListener, OnDestroy, inject } from '@angular/core';
@Directive({selector:'[role="dialog"],[role="alertdialog"]',standalone:true})
export class DemoDialogDirective implements AfterViewInit,OnDestroy {
 private readonly element=inject<ElementRef<HTMLElement>>(ElementRef); private previous:HTMLElement|null=null;
 private controls():HTMLElement[]{return Array.from(this.element.nativeElement.querySelectorAll<HTMLElement>('button:not([disabled]),input:not([disabled]),select:not([disabled]),a[href]')).filter(item=>item.offsetParent!==null);}
 ngAfterViewInit():void{this.previous=document.activeElement instanceof HTMLElement?document.activeElement:null;queueMicrotask(()=>this.controls()[0]?.focus());}
 ngOnDestroy():void{this.previous?.focus();}
 @HostListener('keydown',['$event']) keyboard(event:KeyboardEvent):void{if(event.key!=='Tab')return;const controls=this.controls();const first=controls[0];const last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}
}

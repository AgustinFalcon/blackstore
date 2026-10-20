import { AfterViewInit, Directive, ElementRef, HostListener, OnDestroy, OnChanges, Input, Output, EventEmitter, inject } from '@angular/core';
@Directive({selector:'[role="dialog"],[role="alertdialog"],[demoDialog]',standalone:true})
export class DemoDialogDirective implements AfterViewInit,OnDestroy,OnChanges {
 @Input() demoDialog=true;
 @Output() readonly demoDialogClosed=new EventEmitter<void>();
 private readonly element=inject<ElementRef<HTMLElement>>(ElementRef); private previous:HTMLElement|null=null;
 private initialized=false;
 private controls():HTMLElement[]{return Array.from(this.element.nativeElement.querySelectorAll<HTMLElement>('button:not([disabled]),input:not([disabled]),select:not([disabled]),a[href]')).filter(item=>item.offsetParent!==null);}
 private activate():void{this.previous=document.activeElement instanceof HTMLElement?document.activeElement:null;queueMicrotask(()=>{if(this.demoDialog)this.controls()[0]?.focus();});}
 ngAfterViewInit():void{this.initialized=true;if(this.demoDialog)this.activate();}
 ngOnChanges():void{if(!this.initialized)return;if(this.demoDialog)this.activate();else queueMicrotask(()=>this.previous?.focus());}
 ngOnDestroy():void{this.previous?.focus();}
 @HostListener('keydown',['$event']) keyboard(event:KeyboardEvent):void{if(!this.demoDialog)return;if(event.key==='Escape'){event.preventDefault();this.demoDialogClosed.emit();return;}if(event.key!=='Tab')return;const controls=this.controls();const first=controls[0];const last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}
}

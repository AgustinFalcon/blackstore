import { Component, ElementRef, Input, OnChanges, inject } from '@angular/core';
@Component({selector:'bs-demo-feedback',standalone:true,template:`@if(error){<p class="modal-error" role="alert" tabindex="-1">{{error}}</p>}@if(notice){<p class="modal-success" role="status">{{notice}}</p>}`,styles:[`:host{display:block}.modal-error,.modal-success{padding:12px 14px;border-radius:8px;font-size:13px;line-height:1.6}.modal-error{color:#9a2030;background:#fff0f1}.modal-success{color:#286645;background:#edf8f1}`]})
export class DemoFeedbackComponent implements OnChanges {
 @Input() error='';@Input() notice='';private readonly element=inject<ElementRef<HTMLElement>>(ElementRef);
 ngOnChanges():void{if(this.error)queueMicrotask(()=>this.element.nativeElement.querySelector<HTMLElement>('[role="alert"]')?.focus());}
}

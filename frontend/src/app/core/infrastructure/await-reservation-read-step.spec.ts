import {fakeAsync,tick} from '@angular/core/testing';
import {Subject,of} from 'rxjs';
import {AwaitReservationReadStep,ReservationReadState} from './await-reservation-read-step';

describe('shared bounded reservation read step',()=>{
  it('only Pending advances to another read and Reserved yields once',fakeAsync(()=>{
    const read=jasmine.createSpy().and.returnValues(of(ReservationReadState.Pending),of(ReservationReadState.Reserved));
    const delivered:ReservationReadState[]=[];
    new AwaitReservationReadStep<ReservationReadState>(read,value=>value).execute().subscribe(value=>delivered.push(value));
    expect(delivered).toEqual([]);tick(100);expect(delivered).toEqual([ReservationReadState.Reserved]);expect(read.calls.count()).toBe(2);
  }));
  it('Unknown stops instead of polling or authorizing',()=>{
    const read=jasmine.createSpy().and.returnValue(of(ReservationReadState.Unknown)),error=jasmine.createSpy(),next=jasmine.createSpy();
    new AwaitReservationReadStep<ReservationReadState>(read,value=>value).execute().subscribe({next,error});expect(error).toHaveBeenCalled();expect(next).not.toHaveBeenCalled();expect(read.calls.count()).toBe(1);
  });
  it('generation/context invalidation prevents the next delayed GET',fakeAsync(()=>{
    let current=true;const read=jasmine.createSpy().and.returnValue(of(ReservationReadState.Pending)),error=jasmine.createSpy();
    new AwaitReservationReadStep<ReservationReadState>(read,value=>value,()=>current).execute().subscribe({error});current=false;tick(100);expect(read.calls.count()).toBe(1);expect(error).toHaveBeenCalled();
  }));
  it('a never-returning GET times out and unsubscribes without authorization',fakeAsync(()=>{
    const response=new Subject<ReservationReadState>(),next=jasmine.createSpy(),error=jasmine.createSpy();
    new AwaitReservationReadStep<ReservationReadState>(()=>response,value=>value,()=>true,20,100,5000).execute().subscribe({next,error});tick(5000);
    expect(error).toHaveBeenCalled();expect(response.observed).toBeFalse();response.next(ReservationReadState.Reserved);expect(next).not.toHaveBeenCalled();
  }));
});

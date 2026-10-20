import {Observable,concatMap,defer,of,throwError,timer,timeout} from 'rxjs';

/** Closed observation policy shared by the durable journal and the ticket journey. */
export class ReservationReadState {
  static readonly Pending=new ReservationReadState();
  static readonly Reserved=new ReservationReadState();
  static readonly Unknown=new ReservationReadState();
  private constructor(){}
}

/** Bounded read-only wait. It never creates an intention or releases a journal claim. */
export class AwaitReservationReadStep<T> {
  constructor(private readonly read:()=>Observable<T>,private readonly classify:(value:T)=>ReservationReadState,
    private readonly current:()=>boolean=()=>true,private readonly maxReads=20,private readonly delayMs=100,private readonly deadlineMs=5000){}
  execute(initial?:T):Observable<T>{
    return defer(()=>initial===undefined?this.next(0):this.observe(initial,0)).pipe(timeout({first:this.deadlineMs}));
  }
  private next(read:number):Observable<T>{
    if(!this.current() || read>=this.maxReads)return this.denied();
    return defer(()=>this.current()?this.read():this.denied()).pipe(concatMap(value=>this.observe(value,read+1)));
  }
  private observe(value:T,read:number):Observable<T>{
    if(!this.current())return this.denied();
    const state=this.classify(value);
    if(state===ReservationReadState.Reserved)return of(value);
    if(state!==ReservationReadState.Pending || read>=this.maxReads)return this.denied();
    return timer(this.delayMs).pipe(concatMap(()=>this.next(read)));
  }
  private denied():Observable<never>{return throwError(()=>new Error('Reserva no comprobada o aún pendiente. Consultá; no reenvíes ni cobres.'));}
}

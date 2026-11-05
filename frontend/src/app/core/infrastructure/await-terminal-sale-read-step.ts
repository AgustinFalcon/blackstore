import {Observable,concatMap,defer,of,throwError,timer,timeout} from 'rxjs';

/** Closed observation policy for a terminal sale projection. */
export class TerminalSaleReadState {
  static readonly Pending=new TerminalSaleReadState();
  static readonly Complete=new TerminalSaleReadState();
  static readonly Unknown=new TerminalSaleReadState();
  private constructor(){}
}

/** Bounded GET-only wait for commit/release materialization. */
export class AwaitTerminalSaleReadStep<T> {
  constructor(private readonly read:()=>Observable<T>,private readonly classify:(value:T)=>TerminalSaleReadState,
    private readonly current:()=>boolean=()=>true,private readonly maxReads=20,private readonly delayMs=100,private readonly deadlineMs=5000){}
  execute():Observable<T>{return defer(()=>this.next(0)).pipe(timeout({first:this.deadlineMs}));}
  private next(read:number):Observable<T>{
    if(!this.current()||read>=this.maxReads)return this.denied();
    return defer(()=>this.current()?this.read():this.denied()).pipe(concatMap(value=>this.observe(value,read+1)));
  }
  private observe(value:T,read:number):Observable<T>{
    if(!this.current())return this.denied();
    const state=this.classify(value);
    if(state===TerminalSaleReadState.Complete)return of(value);
    if(state!==TerminalSaleReadState.Pending||read>=this.maxReads)return this.denied();
    return timer(this.delayMs).pipe(concatMap(()=>this.next(read)));
  }
  private denied():Observable<never>{return throwError(()=>new Error('Finalización no comprobada o aún pendiente. Consultá; no reenvíes.'));}
}

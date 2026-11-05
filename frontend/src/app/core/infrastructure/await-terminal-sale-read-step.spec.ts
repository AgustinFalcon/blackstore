import {firstValueFrom,of} from 'rxjs';
import {AwaitTerminalSaleReadStep,TerminalSaleReadState} from './await-terminal-sale-read-step';

describe('AwaitTerminalSaleReadStep',()=>{
  it('polls GET-only pending observations until a terminal projection is complete',async()=>{
    let reads=0;
    const result=await firstValueFrom(new AwaitTerminalSaleReadStep(()=>of(++reads),value=>value===2?
      TerminalSaleReadState.Complete:TerminalSaleReadState.Pending,()=>true,3,0).execute());
    expect(result).toBe(2);expect(reads).toBe(2);
  });
  it('fails closed for an unknown projection and for an invalidated generation',async()=>{
    await expectAsync(firstValueFrom(new AwaitTerminalSaleReadStep(()=>of(1),()=>TerminalSaleReadState.Unknown,()=>true,2,0).execute())).toBeRejected();
    let reads=0;
    await expectAsync(firstValueFrom(new AwaitTerminalSaleReadStep(()=>of(++reads),()=>TerminalSaleReadState.Pending,()=>false,2,0).execute())).toBeRejected();
    expect(reads).toBe(0);
  });
});

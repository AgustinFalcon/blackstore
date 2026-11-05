import { CartDraft, CartLine, CartPhase } from './cart';

describe('cart domain',()=>{
  const line=(variantId='a',quantity=1)=>CartLine.create({variantId,quantity,expectedPriceVersion:'P1',sku:`SKU-${variantId}`,
    productName:`Artículo ${variantId}`,originalUnitPrice:'10.00',discountAmount:'0.00'});
  it('keeps phases closed and fails unknown wire values',()=>{
    expect(CartPhase.fromWire('Editing')).toBe(CartPhase.Editing);expect(CartPhase.fromWire('future')).toBe(CartPhase.Unknown);
  });
  it('adds a repeated variant, changes and removes it with exact totals',()=>{
    const draft=CartDraft.empty().add(line('a',1)).add(line('a',2)).add(line('b',1));
    expect(draft.lines.map(value=>value.quantity)).toEqual([3,1]);expect(draft.total?.decimal).toBe('40.00');
    expect(draft.change('b',2).remove('a').total?.decimal).toBe('20.00');
  });
  it('rejects overflow, an empty freeze and more than 100 variants',()=>{
    expect(()=>CartDraft.empty().freeze()).toThrow();
    let draft=CartDraft.empty();for(let index=0;index<100;index+=1)draft=draft.add(line(`v${index}`));
    expect(()=>draft.add(line('overflow'))).toThrow();
    expect(()=>CartDraft.empty().add(line('a',2147483647)).add(line('a',1))).toThrow();
  });
});

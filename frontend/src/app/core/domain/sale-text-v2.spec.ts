import { SaleTextV2 } from './sale-text-v2';

describe('SaleTextV2',()=>{
  it('uses the exact ASCII edge set and preserves semantic Unicode whitespace',()=>{
    for(const edge of ['\u0009','\u000a','\u000b','\u000c','\u000d',' '])expect(SaleTextV2.trim(`${edge}value${edge}`)).toBe('value');
    for(const edge of ['\u00a0','\u0085','\u2003','\ufeff'])expect(SaleTextV2.trim(`${edge}value${edge}`)).toBe(`${edge}value${edge}`);
    expect(SaleTextV2.trim(' a\t b ')).toBe('a\t b');
  });
  it('rejects NUL and every malformed UTF-16 scalar sequence',()=>{
    for(const value of ['\u0000','\ud800','\udc00','\ud800x','\udc00\ud800'])expect(()=>SaleTextV2.validate(value)).toThrow();
    expect(SaleTextV2.validate('😀')).toBe('😀');
  });
  it('enforces transformed UTF-16 limits without normalizing Unicode',()=>{
    expect(SaleTextV2.ref('r'.repeat(80))).toBe('r'.repeat(80));expect(()=>SaleTextV2.ref('r'.repeat(81))).toThrow();
    expect(SaleTextV2.name(` ${'n'.repeat(250)} `)).toBe('n'.repeat(250));expect(()=>SaleTextV2.name('n'.repeat(251))).toThrow();
    expect(SaleTextV2.reason(` ${'m'.repeat(500)} `)).toBe('m'.repeat(500));expect(()=>SaleTextV2.reason('m'.repeat(501))).toThrow();
    expect(SaleTextV2.name('é')).not.toBe(SaleTextV2.name('e\u0301'));
  });
});

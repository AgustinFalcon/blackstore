import { demoCsv } from './demo-csv';
describe('safe demo CSV', () => {
  it('quotes delimiters, double quotes and newlines', () => { expect(demoCsv([['a;b', 'a"b', 'a\nb']])).toBe('"a;b";"a""b";"a\nb"'); });
  it('neutralizes formula prefixes and hidden prefixes without altering ordinary text', () => {
    for (const input of ['=1+1', '+CMD', '-1', '@SUM', '  =1', '\t=1', '\r@X', '\n+X', '\u0000=1']) expect(demoCsv([[input]])).toBe('"\'' + input + '"');
    expect(demoCsv([['Demo', 12]])).toBe('"Demo";"12"');
  });
});

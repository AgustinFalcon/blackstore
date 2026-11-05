import { completePayment } from '../infrastructure/payment-test.fixture';
import { DemoCalendar, DemoDateRange } from './demo-calendar';
import { DemoQueries } from './demo-queries';
import { DemoMovementKind, DemoPaymentMethod } from './demo-types';
import { DemoWireMapper } from '../infrastructure/demo-wire-mapper';
import { LocalDemoRepository } from '../infrastructure/local-demo-repository';

describe('Buenos Aires business calendar', () => {
  it('uses the local day on both sides of midnight independently of the browser zone', () => {
    const calendar=DemoCalendar.BuenosAires;
    expect(calendar.day('2026-10-21T02:59:59Z')).toBe('2026-10-20');
    expect(calendar.day('2026-10-21T03:00:00Z')).toBe('2026-10-21');
    const period=DemoWireMapper.period('2026-10-20','2026-10-20');
    expect(period.includes('2026-10-21T02:59:59Z')).toBeTrue();
    expect(period.includes('2026-10-21T03:00:00Z')).toBeFalse();
    expect(calendar.display('2026-10-21T02:59:59Z')).toContain('20/10/26');
  });
  it('fails closed for malformed calendar ranges and invalid instants', () => {
    for(const value of ['2026-99-99','2026-02-30',null,12])expect(DemoWireMapper.period(value,'')).toBe(DemoDateRange.Unknown);
    expect(DemoWireMapper.period('2026-10-21','2026-10-20')).toBe(DemoDateRange.Unknown);
    expect(DemoDateRange.Unknown.includes('2026-10-20T10:00:00Z')).toBeFalse();
    expect(DemoDateRange.All.includes('invalid')).toBeFalse();
  });
  it('filters sale and expense totals with the same business-day boundary', () => {
    let now=new Date('2026-10-21T02:59:00Z');const repo=new LocalDemoRepository(()=>now);
    repo.cart('p1',1);const first=completePayment(repo,'before-midnight',DemoPaymentMethod.Card,0,'');repo.moveCash(DemoMovementKind.Expense,10000,'Antes');
    now=new Date('2026-10-21T03:01:00Z');repo.cart('p2',1);const second=completePayment(repo,'after-midnight',DemoPaymentMethod.Card,0,'');repo.moveCash(DemoMovementKind.Expense,20000,'Después');
    const queries=new DemoQueries(repo.snapshot());const firstDay=DemoWireMapper.period('2026-10-20','2026-10-20');const secondDay=DemoWireMapper.period('2026-10-21','2026-10-21');
    const filter={search:'V-10',method:null,status:null};
    expect(queries.sales({...filter,period:firstDay})).toEqual([first]);expect(queries.sales({...filter,period:secondDay})).toEqual([second]);
    expect(queries.expenses(firstDay)).toBe(10000);expect(queries.expenses(secondDay)).toBe(20000);
  });
});

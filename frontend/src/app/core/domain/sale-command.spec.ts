import { SaleAdmissionFamily, SaleAdmissionRoute, SaleCommandFailure, SaleFingerprintVersion, SaleLineFailureCode } from './sale-command';

describe('closed sale admission contracts',()=>{
  it('round trips only known versions, families and routes',()=>{
    for(const version of [SaleFingerprintVersion.AdmissionV1,SaleFingerprintVersion.AdmissionV2])expect(SaleFingerprintVersion.fromWire(version.wire)).toBe(version);
    for(const family of [SaleAdmissionFamily.V2,SaleAdmissionFamily.V3])expect(SaleAdmissionFamily.fromWire(family.wire)).toBe(family);
    for(const route of [SaleAdmissionRoute.ReserveV2,SaleAdmissionRoute.ReserveV3,SaleAdmissionRoute.CommitV2,SaleAdmissionRoute.ReleaseV2])expect(SaleAdmissionRoute.fromWire(route.wire)).toBe(route);
    expect(SaleFingerprintVersion.fromWire('future')).toBe(SaleFingerprintVersion.Unknown);
    expect(SaleAdmissionFamily.fromWire(null)).toBe(SaleAdmissionFamily.Unknown);
    expect(SaleAdmissionRoute.fromWire('https://attacker.invalid')).toBe(SaleAdmissionRoute.Unknown);
  });
  it('maps future failures to fixed Unknown cases',()=>{
    expect(SaleCommandFailure.fromWire('VALIDATION')).toBe(SaleCommandFailure.Validation);
    expect(SaleCommandFailure.fromWire('future')).toBe(SaleCommandFailure.Unknown);
    expect(SaleLineFailureCode.fromWire('PriceMismatch')).toBe(SaleLineFailureCode.PriceMismatch);
    expect(SaleLineFailureCode.fromWire('future')).toBe(SaleLineFailureCode.Unknown);
  });
});

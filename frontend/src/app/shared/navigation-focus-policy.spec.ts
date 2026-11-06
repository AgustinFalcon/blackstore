import { NavigationFocusPolicy } from './navigation-focus-policy';

describe('NavigationFocusPolicy', () => {
  it('allows route focus while the user has not advanced from the navigation origin', () => {
    const navigationOrigin = document.createElement('a');
    expect(NavigationFocusPolicy.mayFocusContent(navigationOrigin, navigationOrigin, document.body)).toBeTrue();
    expect(NavigationFocusPolicy.mayFocusContent(navigationOrigin, document.body, document.body)).toBeTrue();
  });

  it('preserves a control focused after navigation completed', () => {
    const navigationOrigin = document.createElement('a');
    const nextControl = document.createElement('input');
    expect(NavigationFocusPolicy.mayFocusContent(navigationOrigin, nextControl, document.body)).toBeFalse();
  });
});

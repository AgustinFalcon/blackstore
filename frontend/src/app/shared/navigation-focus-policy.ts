export class NavigationFocusPolicy {
  private constructor() {}

  static mayFocusContent(activeAtNavigation: Element | null, currentActive: Element | null, body: HTMLElement): boolean {
    return currentActive === activeAtNavigation || currentActive === body;
  }
}

import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('REDXela: arranque de la aplicación', () => {
  it('arranca y renderiza la raíz standalone', async () => {
    await TestBed.configureTestingModule({ imports: [App] }).compileComponents();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent).toBe('REDXela');
    expect(element.querySelector('main')).not.toBeNull();
  });
});

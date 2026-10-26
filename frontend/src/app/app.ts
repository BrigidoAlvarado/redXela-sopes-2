import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main>
      <p class="eyebrow">Centro de distribución · Quetzaltenango</p>
      <h1>REDXela</h1>
      <p>Pedidos, inventario y recursos compartidos.</p>
      <section aria-labelledby="estado">
        <h2 id="estado">Simulador en preparación</h2>
        <p>Los controles y monitores de operación estarán disponibles próximamente.</p>
      </section>
    </main>
  `,
})
export class App {}

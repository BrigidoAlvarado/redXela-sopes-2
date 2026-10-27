import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { ApiService } from './core/api.service';
import { EstadoService } from './core/estado.service';
import { Snapshot } from './core/models';

describe('REDXela: arranque y navegación', () => {
  it('renderiza la raíz y navega a las secciones operativas sin backend', async () => {
    const empty = { admitidos: 0, completados: 0, procesando: 0, esperandoInventario: 0, esperandoRecursos: 0, errores: 0, niveles: [], recursos: [] };
    const api = { error: signal(''), aviso: signal(''), ocupadas: signal(new Set<string>()), recepciones: signal([]), comparacion: signal(null),
      get: vi.fn(async (ruta: string) => ruta === '/metricas' ? empty : ruta === '/deadlock' ? {estado: 'INACTIVA', deadlock: false} : ruta === '/planificacion' ? {politica: 'PRIORIDAD', intervaloAgingMillis: 300000} : ruta === '/generador' ? {activo: false, intervaloMillis: 30000} : []),
      mensaje: () => 'Error', ejecutar: vi.fn() };
    const estado = { simulacion: signal({estado: 'DETENIDA', corrida: 0, tiempoSimuladoMillis: 0, factorTemporal: 60}), recursos: signal([]), backendDisponible: signal(true),
      ws: { conectado: signal(false), snapshot: signal<Snapshot | null>(null) }, refrescar: vi.fn() };
    await TestBed.configureTestingModule({imports: [App], providers: [provideRouter(routes), {provide: ApiService, useValue: api}, {provide: EstadoService, useValue: estado}]}).compileComponents();
    const fixture = TestBed.createComponent(App); const router = TestBed.inject(Router);
    await router.navigateByUrl('/'); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Panel de operaciones');
    expect(fixture.nativeElement.textContent).toContain('RED');
    await router.navigateByUrl('/recursos'); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Monitor de recursos');
    await router.navigateByUrl('/deadlock'); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Monitor de deadlock');
    fixture.destroy();
  });
});

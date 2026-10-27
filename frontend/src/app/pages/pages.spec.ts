import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { EstadoService } from '../core/estado.service';
import { Deadlock, Metricas } from '../core/models';
import { DashboardPage } from './dashboard';
import { PedidosPage } from './pedidos';
import { InventarioPage } from './inventario';
import { PlanificacionPage } from './planificacion';
import { DeadlockPage } from './deadlock';

describe('Pantallas operativas', () => {
  const deadlock: Deadlock = {estado: 'EN_CURSO', deadlock: true, a: {pedido: 'a', id: 'op-a', posee: ['C01'], espera: 'S01'}, b: {pedido: 'b', id: 'op-b', posee: ['S01'], espera: 'C01'}, ganador: null, explicacion: 'Espera circular'};
  let api: { get: ReturnType<typeof vi.fn>; post: ReturnType<typeof vi.fn>; put: ReturnType<typeof vi.fn>; ejecutar: ReturnType<typeof vi.fn>; error: ReturnType<typeof signal<string>>; aviso: ReturnType<typeof signal<string>>; ocupadas: ReturnType<typeof signal<ReadonlySet<string>>>; recepciones: ReturnType<typeof signal<never[]>>; comparacion: ReturnType<typeof signal<null>>; mensaje: (e: unknown) => string };
  beforeEach(() => {
    api = { error: signal(''), aviso: signal(''), ocupadas: signal<ReadonlySet<string>>(new Set()), recepciones: signal<never[]>([]), comparacion: signal(null), mensaje: String,
      get: vi.fn(async (ruta: string) => {
        if (ruta === '/simulacion') return {estado:'EJECUTANDO', corrida:1, tiempoSimuladoMillis:0, factorTemporal:60};
        if (ruta === '/generador') return {activo:false, intervaloMillis:30000};
        if (ruta === '/planificacion') return {politica:'PRIORIDAD', intervaloAgingMillis:300000};
        if (ruta === '/deadlock') return deadlock;
        if (ruta === '/metricas') return {completados:0, niveles:[], recursos:[]} as Partial<Metricas>;
        if (ruta.startsWith('/recepciones/')) return {ingreso:{id:'recepcion', producto:'GEN-01', cantidad:2}, estado:'EN_COLA'};
        if (ruta.startsWith('/comparaciones/')) return {id:'comp', estado:'COMPLETADA', resultado:{resultados:[], mejores:{}}, error:null};
        return [];
      }),
      post: vi.fn(async (ruta: string) => ruta === '/clientes' ? {id:'cliente', servicioPreferido:'EXPRES'} : ruta === '/pedidos' ? {pedido:{id:'pedido'}} : ruta === '/recepciones' ? {ingreso:{id:'recepcion', producto:'GEN-01', cantidad:2}, estado:'EN_COLA'} : ruta === '/comparaciones' ? {id:'comp', estado:'EN_CURSO', resultado:null, error:null} : deadlock),
      put: vi.fn(async () => ({})), ejecutar: vi.fn(async (_clave: string, accion: () => Promise<unknown>) => accion()) };
    const estado = {simulacion: signal({estado:'EJECUTANDO', corrida:1, tiempoSimuladoMillis:0, factorTemporal:60}), recursos:signal([]), backendDisponible:signal(true), ws:{conectado:signal(false), snapshot:signal(null)}, refrescar:vi.fn(async () => {})};
    TestBed.configureTestingModule({providers:[{provide:ApiService,useValue:api},{provide:EstadoService,useValue:estado}]});
  });
  it('controla el motor mediante sus endpoints', async () => {
    const p = TestBed.runInInjectionContext(() => new DashboardPage());
    for (const accion of ['iniciar', 'pausar', 'detener']) await p.controlar(accion);
    expect(api.post.mock.calls.map(c => c[0])).toEqual(['/simulacion/iniciar','/simulacion/pausar','/simulacion/detener']);
  });
  it('registra clientes y pedidos y controla el generador', async () => {
    const p = TestBed.runInInjectionContext(() => new PedidosPage());
    p.nombre = 'Ana'; p.correo = 'ana@example.com'; await p.crearCliente();
    expect(api.post).toHaveBeenCalledWith('/clientes', expect.objectContaining({nombre:'Ana'}));
    p.clienteId = 'cliente'; p.lineas = [{productoId:'GEN-01',cantidad:2}]; await p.crearPedido();
    expect(api.post).toHaveBeenCalledWith('/pedidos', expect.objectContaining({clienteId:'cliente'}));
    p.lineas = [{productoId:'GEN-01',cantidad:1},{productoId:'GEN-01',cantidad:2}]; expect(p.lineasValidas()).toBe(false);
    await p.controlarGenerador('iniciar'); await p.configurarGenerador();
    expect(api.post).toHaveBeenCalledWith('/generador/iniciar'); expect(api.put).toHaveBeenCalledWith('/generador/intervalo', {intervaloMillis:30000});
  });
  it('consulta inventario y registra una recepción asíncrona', async () => {
    const p = TestBed.runInInjectionContext(() => new InventarioPage());
    await p.refrescar(); expect(api.get).toHaveBeenCalledWith('/inventario'); expect(api.get).toHaveBeenCalledWith('/inventario/ubicaciones');
    p.productoId = 'GEN-01'; p.cantidad = 2; await p.recibir();
    expect(api.post).toHaveBeenCalledWith('/recepciones', {productoId:'GEN-01',cantidad:2});
    expect(p.recepciones()[0]?.estado).toBe('EN_COLA');
  });
  it('cambia política y consulta la comparación', async () => {
    const p = TestBed.runInInjectionContext(() => new PlanificacionPage());
    await p.refrescar(); p.politica = 'SJF'; await p.guardar();
    expect(api.put).toHaveBeenCalledWith('/planificacion', {politica:'SJF', intervaloAgingMillis:300000});
    await p.comparar(); await Promise.resolve();
    expect(api.post).toHaveBeenCalledWith('/comparaciones', {observacionMillis:60000, factor:100});
    expect(api.get).toHaveBeenCalledWith('/comparaciones/comp'); p.ngOnDestroy();
  });
  it('presenta métricas sin inventar promedios cuando faltan datos', async () => {
    const fixture = TestBed.createComponent(PlanificacionPage);
    fixture.componentInstance.metricas.set({completados:0, niveles:[], recursos:[]} as unknown as Metricas);
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Rendimiento general');
    expect(fixture.nativeElement.textContent).toContain('Sin datos suficientes');
    fixture.destroy();
  });
  it('observa el deadlock y envía el ganador elegido', async () => {
    const p = TestBed.runInInjectionContext(() => new DeadlockPage()); await p.refrescar();
    expect(p.conflicto()?.a?.posee).toEqual(['C01']);
    await p.resolver('a'); expect(api.post).toHaveBeenCalledWith('/deadlock/resolver', {pedidoGanador:'a'});
    p.pedidoA = 'a'; p.pedidoB = 'b'; await p.iniciar();
    expect(api.post).toHaveBeenCalledWith('/deadlock/iniciar', {pedidoA:'a', pedidoB:'b'});
  });
});

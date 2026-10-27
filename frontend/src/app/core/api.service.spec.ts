import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService, BACKEND } from './api.service';

describe('API de REDXela', () => {
  beforeEach(() => TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]}));
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('consulta estado y envía controles al backend', async () => {
    const api = TestBed.inject(ApiService), http = TestBed.inject(HttpTestingController);
    const estado = api.get<{estado: string}>('/simulacion');
    http.expectOne(`${BACKEND}/api/simulacion`).flush({estado: 'DETENIDA'});
    expect((await estado).estado).toBe('DETENIDA');
    for (const accion of ['iniciar', 'pausar', 'detener']) {
      const resultado = api.post(`/simulacion/${accion}`);
      const req = http.expectOne(`${BACKEND}/api/simulacion/${accion}`); expect(req.request.method).toBe('POST'); req.flush({estado: accion}); await resultado;
    }
  });
  it('muestra el mensaje de error y evita solicitudes duplicadas', async () => {
    const api = TestBed.inject(ApiService), http = TestBed.inject(HttpTestingController);
    const primera = api.ejecutar('pedido', () => api.post('/pedidos', {}));
    expect(await api.ejecutar('pedido', () => api.post('/pedidos', {}))).toBeUndefined();
    http.expectOne(`${BACKEND}/api/pedidos`).flush({codigo: 'OPERACION_NO_PERMITIDA', mensaje: 'Simulación detenida'}, {status: 409, statusText: 'Conflict'});
    expect(await primera).toBeUndefined(); expect(api.error()).toBe('Simulación detenida'); expect(api.ocupadas().size).toBe(0);
  });
});

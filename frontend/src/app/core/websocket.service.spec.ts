import { TestBed } from '@angular/core/testing';
import { Client, IMessage } from '@stomp/stompjs';
import { CLIENTE_STOMP, WebsocketService } from './websocket.service';

describe('WebSocket compartido', () => {
  it('se suscribe al destino, publica snapshots y limpia la conexión', () => {
    let recibir: ((m: IMessage) => void) | undefined;
    const cliente = { activate: vi.fn(), deactivate: vi.fn().mockResolvedValue(undefined), subscribe: vi.fn((_canal: string, fn: (m: IMessage) => void) => { recibir = fn; }), onConnect: undefined, onWebSocketClose: undefined } as unknown as Client;
    TestBed.configureTestingModule({providers: [{provide: CLIENTE_STOMP, useValue: cliente}]});
    const servicio = TestBed.inject(WebsocketService);
    expect(cliente.activate).toHaveBeenCalledOnce();
    cliente.onConnect({} as never);
    expect(servicio.conectado()).toBe(true); expect(cliente.subscribe).toHaveBeenCalledWith('/topic/estado', expect.any(Function));
    recibir!({body: JSON.stringify({simulacion: {estado: 'EJECUTANDO'}, recursos: []})} as IMessage);
    expect(servicio.snapshot()?.simulacion.estado).toBe('EJECUTANDO');
    cliente.onWebSocketClose({} as CloseEvent); expect(servicio.conectado()).toBe(false); expect(servicio.snapshot()).toBeNull();
    servicio.ngOnDestroy(); expect(cliente.deactivate).toHaveBeenCalledOnce();
  });
});

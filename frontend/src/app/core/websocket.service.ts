import { Injectable, InjectionToken, OnDestroy, inject, signal } from '@angular/core';
import { Client } from '@stomp/stompjs';
import { Snapshot } from './models';
import { BACKEND } from './api.service';

export const CLIENTE_STOMP = new InjectionToken<Client>('cliente STOMP', {
  providedIn: 'root', factory: () => new Client({ brokerURL: `${BACKEND.replace(/^http/, 'ws')}/ws`, reconnectDelay: 5000, heartbeatIncoming: 10000, heartbeatOutgoing: 10000 }),
});

@Injectable({ providedIn: 'root' })
export class WebsocketService implements OnDestroy {
  readonly conectado = signal(false);
  readonly snapshot = signal<Snapshot | null>(null);
  private cliente = inject(CLIENTE_STOMP);
  constructor() {
    this.cliente.onConnect = () => {
      this.conectado.set(true);
      this.cliente.subscribe('/topic/estado', mensaje => {
        try { this.snapshot.set(JSON.parse(mensaje.body) as Snapshot); }
        catch { /* Una trama inválida no debe interrumpir REST ni la reconexión. */ }
      });
    };
    this.cliente.onWebSocketClose = () => { this.conectado.set(false); this.snapshot.set(null); };
    this.cliente.onStompError = () => { this.conectado.set(false); this.snapshot.set(null); };
    this.cliente.activate();
  }
  ngOnDestroy(): void { void this.cliente.deactivate(); }
}

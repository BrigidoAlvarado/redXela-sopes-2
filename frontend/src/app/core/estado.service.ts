import { effect, Injectable, OnDestroy, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Recurso, Simulacion } from './models';
import { WebsocketService } from './websocket.service';

@Injectable({ providedIn: 'root' })
export class EstadoService implements OnDestroy {
  private api = inject(ApiService);
  readonly ws = inject(WebsocketService);
  readonly simulacion = signal<Simulacion | null>(null);
  readonly recursos = signal<Recurso[]>([]);
  readonly backendDisponible = signal(false);
  private intervalo: ReturnType<typeof setInterval>;
  private consultando = false;
  constructor() {
    effect(() => { const s = this.ws.snapshot(); if (s) { this.simulacion.set(s.simulacion); this.recursos.set(s.recursos); this.backendDisponible.set(true); } });
    void this.refrescar();
    this.intervalo = setInterval(() => { if (!this.ws.conectado()) void this.refrescar(); }, 5000);
  }
  async refrescar(): Promise<void> {
    if (this.consultando) return;
    this.consultando = true;
    try { const [s, r] = await Promise.all([this.api.get<Simulacion>('/simulacion'), this.api.get<Recurso[]>('/recursos')]); this.simulacion.set(s); this.recursos.set(r); this.backendDisponible.set(true); }
    catch { this.backendDisponible.set(false); }
    finally { this.consultando = false; }
  }
  ngOnDestroy(): void { clearInterval(this.intervalo); }
}

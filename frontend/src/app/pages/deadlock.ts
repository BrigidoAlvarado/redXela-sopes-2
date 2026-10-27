import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Actualizacion } from '../core/actualizacion';
import { ApiService } from '../core/api.service';
import { EstadoService } from '../core/estado.service';
import { Deadlock, PedidoVista, corto } from '../core/models';

@Component({ selector: 'app-deadlock', standalone: true, imports: [FormsModule], templateUrl: './deadlock.html' })
export class DeadlockPage extends Actualizacion {
  readonly api = inject(ApiService); readonly estado = inject(EstadoService); readonly corto = corto;
  readonly pedidos = signal<PedidoVista[]>([]); readonly datos = signal<Deadlock | null>(null);
  pedidoA = ''; pedidoB = '';
  conflicto(): Deadlock | null { return this.estado.ws.conectado() ? this.estado.ws.snapshot()?.deadlock ?? this.datos() : this.datos(); }
  elegibles(): PedidoVista[] { return this.pedidos().filter(p => p.progreso && !['COMPLETADO','ERROR','CANCELADO'].includes(p.progreso.estado) && p.pedido.lineas.some(l => l.producto.tipo === 'GENERAL')); }
  protected async cargar(): Promise<void> {
    try { const [p, d] = await Promise.all([this.api.get<PedidoVista[]>('/pedidos'), this.api.get<Deadlock>('/deadlock')]); this.pedidos.set(p); this.datos.set(d); }
    catch (e) { this.api.error.set(this.api.mensaje(e)); }
  }
  async iniciar(): Promise<void> {
    if (!this.pedidoA || !this.pedidoB || this.pedidoA === this.pedidoB) { this.api.error.set('Selecciona dos pedidos distintos.'); return; }
    const d = await this.api.ejecutar('deadlock', () => this.api.post<Deadlock>('/deadlock/iniciar', {pedidoA: this.pedidoA, pedidoB: this.pedidoB}), 'Demostración solicitada.');
    if (d) this.datos.set(d); await this.refrescar();
  }
  async resolver(id: string): Promise<void> {
    const d = await this.api.ejecutar('resolver', () => this.api.post<Deadlock>('/deadlock/resolver', {pedidoGanador: id}), 'Elección enviada. Espera la recuperación del backend.');
    if (d) this.datos.set(d); await this.refrescar();
  }
}

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { EstadoService } from '../core/estado.service';
import { Actualizacion } from '../core/actualizacion';
import { Metricas, PedidoVista, tiempo, corto } from '../core/models';

@Component({ selector: 'app-dashboard', standalone: true, imports: [FormsModule, RouterLink], templateUrl: './dashboard.html' })
export class DashboardPage extends Actualizacion {
  readonly api = inject(ApiService); readonly estado = inject(EstadoService);
  readonly metricas = signal<Metricas | null>(null); readonly pedidos = signal<PedidoVista[]>([]);
  readonly tiempo = tiempo; readonly corto = corto;
  factor = 60;
  protected async cargar(): Promise<void> {
    try { const [m, p] = await Promise.all([this.api.get<Metricas>('/metricas'), this.api.get<PedidoVista[]>('/pedidos')]); this.metricas.set(m); this.pedidos.set(p.slice(-6).reverse()); }
    catch (e) { this.api.error.set(this.api.mensaje(e)); }
  }
  async controlar(accion: string): Promise<void> {
    await this.api.ejecutar('simulacion', () => this.api.post(`/simulacion/${accion}`), `Simulación: ${accion}.`);
    await this.estado.refrescar(); await this.refrescar();
  }
  async velocidad(): Promise<void> {
    if (!Number.isFinite(this.factor) || this.factor <= 0) { this.api.error.set('El factor debe ser mayor que cero.'); return; }
    await this.api.ejecutar('velocidad', () => this.api.put('/simulacion/velocidad', {factor: this.factor}), 'Velocidad actualizada.'); await this.estado.refrescar();
  }
}

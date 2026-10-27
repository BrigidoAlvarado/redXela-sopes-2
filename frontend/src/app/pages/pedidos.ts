import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { Actualizacion } from '../core/actualizacion';
import { ApiService } from '../core/api.service';
import { EstadoService } from '../core/estado.service';
import { Cliente, Cola, Deadlock, Generador, NIVELES, PedidoVista, Producto, corto, tiempo } from '../core/models';

@Component({ selector: 'app-pedidos', standalone: true, imports: [FormsModule, DecimalPipe], templateUrl: './pedidos.html' })
export class PedidosPage extends Actualizacion {
  readonly api = inject(ApiService); readonly estado = inject(EstadoService);
  readonly clientes = signal<Cliente[]>([]); readonly productos = signal<Producto[]>([]); readonly pedidos = signal<PedidoVista[]>([]);
  readonly cola = signal<Cola[]>([]); readonly generador = signal<Generador | null>(null);
  readonly conflicto = signal<Deadlock | null>(null);
  readonly niveles = NIVELES; readonly corto = corto; readonly tiempo = tiempo;
  nombre = ''; correo = ''; servicio = 'ESTANDAR'; clienteId = ''; nivel = 'ESTANDAR';
  lineas: { productoId: string; cantidad: number }[] = [{productoId: '', cantidad: 1}];
  intervaloSegundos = 30; detalle = '';
  private intervaloInicializado = false;
  protected async cargar(): Promise<void> {
    try {
      const [c, p, pedidos, cola, g, conflicto] = await Promise.all([this.api.get<Cliente[]>('/clientes'), this.api.get<Producto[]>('/catalogo'), this.api.get<PedidoVista[]>('/pedidos'), this.api.get<Cola[]>('/pedidos/cola'), this.api.get<Generador>('/generador'), this.api.get<Deadlock>('/deadlock')]);
      if (!this.vistaActiva) return;
      this.clientes.set(c); this.productos.set(p); this.pedidos.set(pedidos.slice().reverse()); this.cola.set(cola); this.generador.set(g); this.conflicto.set(conflicto);
      if (!this.intervaloInicializado) { this.intervaloSegundos = g.intervaloMillis / 1000; this.intervaloInicializado = true; }
    } catch (e) { if (this.vistaActiva) this.api.error.set(this.api.mensaje(e)); }
  }
  unidades(p: PedidoVista): number { return p.pedido.lineas.reduce((s, l) => s + l.cantidad, 0); }
  estadoPedido(p: PedidoVista): string {
    const d = this.estado.ws.snapshot()?.deadlock ?? this.conflicto();
    if (d && (d.a?.pedido === p.pedido.id || d.b?.pedido === p.pedido.id)) {
      if (d.deadlock) return 'BLOQUEADO_DEADLOCK';
      if (d.estado === 'RECUPERANDO') return 'RECUPERANDO';
    }
    return p.progreso?.estado || 'CREADO';
  }
  lineasValidas(): boolean { return this.lineas.length > 0 && this.lineas.every(l => !!l.productoId && Number.isInteger(l.cantidad) && l.cantidad > 0) && new Set(this.lineas.map(l => l.productoId)).size === this.lineas.length; }
  async crearCliente(): Promise<void> {
    if (!this.nombre.trim() || !this.correo.trim()) { this.api.error.set('Completa el nombre y el correo.'); return; }
    const c = await this.api.ejecutar('cliente', () => this.api.post<Cliente>('/clientes', {nombre: this.nombre, correo: this.correo, servicioPreferido: this.servicio}), 'Cliente registrado.');
    if (c && this.vistaActiva) { this.clienteId = c.id; this.nivel = c.servicioPreferido; this.nombre = ''; this.correo = ''; await this.refrescar(); }
  }
  async crearPedido(): Promise<void> {
    if (!this.clienteId || !this.lineasValidas()) { this.api.error.set('Selecciona cliente y productos distintos con cantidades positivas.'); return; }
    const p = await this.api.ejecutar('pedido', () => this.api.post<PedidoVista>('/pedidos', {clienteId: this.clienteId, nivelServicio: this.nivel, lineas: this.lineas}), 'Pedido admitido.');
    if (p && this.vistaActiva) { this.lineas = [{productoId: '', cantidad: 1}]; await this.refrescar(); }
  }
  async controlarGenerador(accion: string): Promise<void> {
    await this.api.ejecutar('generador', () => this.api.post(`/generador/${accion}`), `Generador: ${accion}.`); await this.refrescar();
  }
  async configurarGenerador(): Promise<void> {
    if (!Number.isFinite(this.intervaloSegundos) || this.intervaloSegundos <= 0) { this.api.error.set('El intervalo debe ser positivo.'); return; }
    await this.api.ejecutar('intervalo', () => this.api.put('/generador/intervalo', {intervaloMillis: Math.round(this.intervaloSegundos * 1000)}), 'Intervalo actualizado.'); await this.refrescar();
  }
}

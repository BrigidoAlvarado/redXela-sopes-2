import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Actualizacion } from '../core/actualizacion';
import { ApiService } from '../core/api.service';
import { EstadoService } from '../core/estado.service';
import { Existencia, Producto, Recepcion, Ubicacion, corto } from '../core/models';

@Component({ selector: 'app-inventario', standalone: true, imports: [FormsModule], templateUrl: './inventario.html' })
export class InventarioPage extends Actualizacion {
  readonly api = inject(ApiService); readonly estado = inject(EstadoService); readonly corto = corto;
  readonly inventario = signal<Existencia[]>([]); readonly ubicaciones = signal<Ubicacion[]>([]);
  readonly productos = signal<Producto[]>([]); readonly recepciones = this.api.recepciones;
  busqueda = ''; productoId = ''; cantidad = 1; soloOcupadas = true; recepcionId = '';
  protected async cargar(): Promise<void> {
    try {
      const [i, u, p, r] = await Promise.all([this.api.get<Existencia[]>('/inventario'), this.api.get<Ubicacion[]>('/inventario/ubicaciones'), this.api.get<Producto[]>('/catalogo'), Promise.all(this.recepciones().map(x => this.api.get<Recepcion>(`/recepciones/${x.ingreso.id}`)))]);
      this.inventario.set(i); this.ubicaciones.set(u); this.productos.set(p); this.recepciones.set(r);
    } catch (e) { this.api.error.set(this.api.mensaje(e)); }
  }
  filtrados(): Existencia[] { const q = this.busqueda.toLowerCase().trim(); return this.inventario().filter(x => `${x.producto.id} ${x.producto.nombre} ${x.producto.tipo}`.toLowerCase().includes(q)); }
  visibles(): Ubicacion[] { return this.soloOcupadas ? this.ubicaciones().filter(u => u.ocupado > 0) : this.ubicaciones(); }
  async recibir(): Promise<void> {
    if (!this.productoId || !Number.isInteger(this.cantidad) || this.cantidad < 1) { this.api.error.set('Selecciona un producto y una cantidad entera positiva.'); return; }
    const r = await this.api.ejecutar('recepcion', () => this.api.post<Recepcion>('/recepciones', {productoId: this.productoId, cantidad: this.cantidad}), 'Recepción aceptada; sigue su estado en esta pantalla.');
    if (r) { this.recepciones.update(lista => [r, ...lista]); this.cantidad = 1; await this.refrescar(); }
  }
  async consultarRecepcion(): Promise<void> {
    if (!this.recepcionId.trim()) return;
    const r = await this.api.ejecutar('consultar-recepcion', () => this.api.get<Recepcion>(`/recepciones/${encodeURIComponent(this.recepcionId.trim())}`));
    if (r) { this.recepciones.update(lista => [r, ...lista.filter(x => x.ingreso.id !== r.ingreso.id)]); this.recepcionId = ''; }
  }
}

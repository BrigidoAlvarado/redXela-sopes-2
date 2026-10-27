import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Actualizacion } from '../core/actualizacion';
import { ApiService } from '../core/api.service';
import { Cola, Comparacion, Metricas, Planificacion, corto, tiempo } from '../core/models';

@Component({ selector: 'app-planificacion', standalone: true, imports: [FormsModule, DecimalPipe], templateUrl: './planificacion.html' })
export class PlanificacionPage extends Actualizacion {
  readonly api = inject(ApiService); readonly tiempo = tiempo; readonly corto = corto;
  readonly plan = signal<Planificacion | null>(null); readonly metricas = signal<Metricas | null>(null);
  readonly cola = signal<Cola[]>([]); readonly comparacion = this.api.comparacion;
  politica = 'PRIORIDAD'; agingMinutos = 5; observacionSegundos = 60; factor = 100;
  private inicializada = false;
  private poll?: ReturnType<typeof setInterval>; private consultandoComparacion = false; private destruida = false;
  override ngOnInit(): void { super.ngOnInit(); if (this.comparacion()?.estado === 'EN_CURSO') this.iniciarConsulta(); }
  protected async cargar(): Promise<void> {
    try { const [p, m, c] = await Promise.all([this.api.get<Planificacion>('/planificacion'), this.api.get<Metricas>('/metricas'), this.api.get<Cola[]>('/pedidos/cola')]); this.plan.set(p); this.metricas.set(m); this.cola.set(c); if (!this.inicializada) { this.politica = p.politica; this.agingMinutos = p.intervaloAgingMillis / 60000; this.inicializada = true; } }
    catch (e) { this.api.error.set(this.api.mensaje(e)); }
  }
  async guardar(): Promise<void> {
    if (!Number.isFinite(this.agingMinutos) || this.agingMinutos <= 0) { this.api.error.set('El intervalo de aging debe ser positivo.'); return; }
    await this.api.ejecutar('plan', () => this.api.put('/planificacion', {politica: this.politica, intervaloAgingMillis: Math.round(this.agingMinutos * 60000)}), 'Política actualizada.'); await this.refrescar();
  }
  async comparar(): Promise<void> {
    if (!Number.isFinite(this.observacionSegundos) || !Number.isFinite(this.factor) || this.observacionSegundos <= 0 || this.observacionSegundos > 86400 || this.factor <= 0 || this.factor > 1000000 || this.observacionSegundos / this.factor > 5) { this.api.error.set('Usa un tiempo positivo hasta 24 horas simuladas y un factor que limite cada política a 5 segundos reales.'); return; }
    const c = await this.api.ejecutar('comparar', () => this.api.post<Comparacion>('/comparaciones', {observacionMillis: Math.round(this.observacionSegundos * 1000), factor: this.factor}), 'Comparación iniciada.');
    if (c) { this.comparacion.set(c); if (!this.destruida) this.iniciarConsulta(); }
  }
  private iniciarConsulta(): void {
    if (this.poll) clearInterval(this.poll);
    void this.consultarComparacion();
    this.poll = setInterval(() => void this.consultarComparacion(), 1500);
  }
  private async consultarComparacion(): Promise<void> {
    const id = this.comparacion()?.id; if (!id || this.consultandoComparacion || this.destruida) return;
    this.consultandoComparacion = true;
    try {
      const c = await this.api.get<Comparacion>(`/comparaciones/${id}`); if (this.destruida) return; this.comparacion.set(c);
      if (c.estado !== 'EN_CURSO' && this.poll) { clearInterval(this.poll); this.poll = undefined; }
      if (c.estado === 'ERROR') this.api.error.set(c.error || 'No se pudo completar la comparación.');
    } catch (e) { this.api.error.set(this.api.mensaje(e)); }
    finally { this.consultandoComparacion = false; }
  }
  override ngOnDestroy(): void { this.destruida = true; if (this.poll) clearInterval(this.poll); super.ngOnDestroy(); }
  mejores(): [string, string[]][] { return Object.entries(this.comparacion()?.resultado?.mejores ?? {}); }
}

import { Directive, OnDestroy, OnInit } from '@angular/core';

/** Cada vista consulta solo mientras está visible y evita solicitudes simultáneas. */
@Directive()
export abstract class Actualizacion implements OnInit, OnDestroy {
  private temporizador?: ReturnType<typeof setInterval>;
  private consultando = false;
  protected vistaActiva = true;
  protected abstract cargar(): Promise<void>;
  async refrescar(): Promise<void> {
    if (this.consultando || !this.vistaActiva) return;
    this.consultando = true;
    try { await this.cargar(); } finally { this.consultando = false; }
  }
  ngOnInit(): void { void this.refrescar(); this.temporizador = setInterval(() => void this.refrescar(), 5000); }
  ngOnDestroy(): void { this.vistaActiva = false; if (this.temporizador) clearInterval(this.temporizador); }
}

import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApiService } from './core/api.service';
import { EstadoService } from './core/estado.service';

@Component({
  selector: 'app-root', standalone: true, imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
  <div class="layout">
    <aside class="sidebar">
      <a class="brand" routerLink="/"><span class="brand-mark">R</span><span>RED<span class="accent">X</span>ela<small>CENTRO DE DISTRIBUCIÓN</small></span></a>
      <p class="nav-label">OPERACIONES</p>
      <nav aria-label="Navegación principal">
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}"><span>▦</span> Panel general</a>
        <a routerLink="/pedidos" routerLinkActive="active"><span>▤</span> Pedidos</a>
        <a routerLink="/inventario" routerLinkActive="active"><span>▥</span> Inventario</a>
        <a routerLink="/recursos" routerLinkActive="active"><span>◫</span> Recursos</a>
        <a routerLink="/planificacion" routerLinkActive="active"><span>▧</span> Planificación y métricas</a>
        <a routerLink="/deadlock" routerLinkActive="active"><span>◇</span> Deadlock</a>
      </nav>
      <div class="sidebar-footer"><span class="dot" [class.live]="estado.ws.conectado()"></span> {{ estado.ws.conectado() ? 'Tiempo real conectado' : 'Tiempo real desconectado' }}<small>Sistemas Operativos 2 · CUNOC</small></div>
    </aside>
    <main class="workspace">
      <header class="topbar"><div><span class="top-kicker">CENTRO DE CONTROL</span><strong>Operaciones de REDXela</strong></div><div class="top-status"><span class="dot" [class.live]="estado.backendDisponible()"></span>{{ estado.backendDisponible() ? 'Backend conectado' : 'Backend desconectado' }}</div></header>
      @if (api.error()) { <div class="notice error" role="alert"><span>{{api.error()}}</span><button type="button" (click)="api.error.set('')" aria-label="Cerrar error">×</button></div> }
      @if (api.aviso()) { <div class="notice success" role="status"><span>{{api.aviso()}}</span><button type="button" (click)="api.aviso.set('')" aria-label="Cerrar aviso">×</button></div> }
      @if (!estado.ws.conectado()) { <div class="connection-note">Actualización en tiempo real desconectada. REST sigue disponible; se intentará reconectar.</div> }
      <router-outlet />
    </main>
  </div>`
})
export class App { readonly estado = inject(EstadoService); readonly api = inject(ApiService); }

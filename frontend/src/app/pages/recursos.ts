import { Component, inject } from '@angular/core';
import { EstadoService } from '../core/estado.service';
@Component({ selector: 'app-recursos', standalone: true, templateUrl: './recursos.html' })
export class RecursosPage {
  readonly estado = inject(EstadoService);
  readonly tipos = [ ['ESTACION_EMPAQUE','Estaciones de empaque'], ['AREA_CARGA','Áreas de carga'], ['ENCARGADO_BODEGA','Encargados de bodega'], ['MONTACARGAS','Montacargas'], ['ESTACION_CALIDAD','Control de calidad'], ['ESCANER','Escáner compartido'] ];
  grupo(tipo: string) { return this.estado.recursos().filter(r => r.tipo === tipo); }
}

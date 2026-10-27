import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, timeout, TimeoutError } from 'rxjs';
import { Comparacion, Recepcion } from './models';

export const BACKEND = 'http://localhost:8080';
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  readonly error = signal('');
  readonly aviso = signal('');
  readonly recepciones = signal<Recepcion[]>([]);
  readonly comparacion = signal<Comparacion | null>(null);
  readonly ocupadas = signal<ReadonlySet<string>>(new Set());
  get<T>(ruta: string): Promise<T> { return firstValueFrom(this.http.get<T>(`${BACKEND}/api${ruta}`).pipe(timeout(10000))); }
  post<T>(ruta: string, cuerpo: unknown = {}): Promise<T> { return firstValueFrom(this.http.post<T>(`${BACKEND}/api${ruta}`, cuerpo).pipe(timeout(10000))); }
  put<T>(ruta: string, cuerpo: unknown): Promise<T> { return firstValueFrom(this.http.put<T>(`${BACKEND}/api${ruta}`, cuerpo).pipe(timeout(10000))); }
  mensaje(error: unknown): string {
    if (error instanceof TimeoutError) return 'La solicitud tardó demasiado. Comprueba el estado antes de reintentar.';
    if (error instanceof HttpErrorResponse) return error.error?.mensaje || (error.status === 0 ? 'No se puede conectar con el backend.' : `Error HTTP ${error.status}`);
    return error instanceof Error ? error.message : 'No se pudo completar la operación.';
  }
  async ejecutar<T>(clave: string, accion: () => Promise<T>, exito?: string): Promise<T | undefined> {
    if (this.ocupadas().has(clave)) return undefined;
    this.ocupadas.update(v => new Set([...v, clave])); this.error.set(''); this.aviso.set('');
    try { const resultado = await accion(); if (exito) this.aviso.set(exito); return resultado; }
    catch (error) { this.error.set(this.mensaje(error)); return undefined; }
    finally { this.ocupadas.update(v => { const n = new Set(v); n.delete(clave); return n; }); }
  }
}

export interface Simulacion { estado: string; corrida: number; tiempoSimuladoMillis: number; factorTemporal: number }
export interface Recurso { id: string; tipo: string; disponible: boolean; propietario: string | null }
export interface Cliente { id: string; nombre: string; correo: string; servicioPreferido: string }
export interface Producto { id: string; nombre: string; tipo: string; volumenPorUnidad: number }
export interface Linea { producto: Producto; cantidad: number }
export interface Progreso { estado: string; etapa: string | null; porcentaje: number; pasosCompletados: number; pasosTotales: number; recursos: string[]; restanteEstimadoMillis: number | null; esperaAcumuladaMillis: number; procesamientoAcumuladoMillis: number; error: string | null }
export interface PedidoVista { pedido: { id: string; cliente: Cliente; nivelServicio: string; lineas: Linea[]; secuenciaEntrada: number }; progreso: Progreso | null }
export interface Cola { pedidoId: string; nivelServicio: string; unidades: number; orden: number; estado: string; esperaAcumuladaMillis: number; prioridadEfectiva: number | null; motivo: string }
export interface Existencia { producto: Producto; fisico: number; reservado: number; disponible: number }
export interface Ubicacion { id: string; capacidad: number; ocupado: number; fragmentos: { lote: string; producto: string; cantidad: number }[] }
export interface Recepcion { ingreso: { id: string; producto: string; cantidad: number }; estado: string; detalle: string | null }
export interface Generador { activo: boolean; intervaloMillis: number }
export interface Planificacion { politica: string; intervaloAgingMillis: number }
export interface NivelMetrica { nivel: string; admitidos: number; completados: number; primeraAtencionPromedioMillis: number; cumplen: number; porcentajeCumplimiento: number; pendientesVencidos: number }
export interface Utilizacion { tipo: string; unidades: number; tiempoOcupadoMillis: number; porcentaje: number }
export interface Metricas { corrida: number; tiempoObservadoMillis: number; admitidos: number; completados: number; procesando: number; esperandoInventario: number; esperandoRecursos: number; errores: number; cancelados: number; primeraAtencionPromedioMillis: number; esperaPromedioMillis: number; procesamientoPromedioMillis: number; totalPromedioMillis: number; cumplimiento: number; niveles: NivelMetrica[]; recursos: Utilizacion[] }
export interface Operacion { pedido: string; id: string; posee: string[]; espera: string | null }
export interface Deadlock { estado: string; deadlock: boolean; a: Operacion | null; b: Operacion | null; ganador: string | null; explicacion: string }
export interface Snapshot { simulacion: Simulacion; pedidos: Pick<Metricas, 'admitidos' | 'completados' | 'procesando' | 'esperandoInventario' | 'esperandoRecursos' | 'errores'>; recursos: Recurso[]; deadlock: Deadlock }
export interface Comparacion { id: string; estado: string; error: string | null; resultado: { escenario: unknown[]; observacionMillis: number; factor: number; resultados: { politica: string; metricas: Metricas; pendientes: number }[]; mejores: Record<string, string[]> } | null }
export const NIVELES = ['EXPRES', 'PRIORITARIO', 'ESTANDAR', 'PROGRAMADO', 'ECONOMICO'];
export function tiempo(ms: number | null | undefined): string {
  if (ms == null) return '—';
  const s = Math.floor(Math.max(0, ms) / 1000), h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
  return [h ? `${h} h` : '', m ? `${m} min` : '', `${s % 60} s`].filter(Boolean).join(' ');
}
export function corto(id: string | null | undefined): string { return id ? id.slice(0, 8).toUpperCase() : '—'; }

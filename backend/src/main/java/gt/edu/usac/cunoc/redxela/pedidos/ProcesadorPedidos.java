package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import gt.edu.usac.cunoc.redxela.planificacion.PlanificadorPedidos;
import gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.catalogo.TipoMercancia;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArraySet;

/** Coordina etapas y selecciona sobre la misma colección de pedidos pendientes. */
public final class ProcesadorPedidos implements AutoCloseable {
    private final PedidoService pedidos;
    private final MotorSimulacion motor;
    private final GestorRecursos recursos;
    private final InventarioService inventario;
    private final Map<UUID, Ejecucion> ejecuciones = new LinkedHashMap<>();
    private final Semaphore aviso = new Semaphore(0);
    private final CopyOnWriteArraySet<Semaphore> avisosExternos = new CopyOnWriteArraySet<>();
    public void registrarAvisos(Semaphore aviso) { avisosExternos.add(Objects.requireNonNull(aviso)); }
    public void retirarAvisos(Semaphore aviso) { avisosExternos.remove(aviso); }
    private void avisarExternos() { avisosExternos.forEach(Semaphore::release); }
    private final Thread coordinador;
    private volatile boolean cerrado;
    private PoliticaPlanificacion politica = PoliticaPlanificacion.PRIORIDAD;
    private Duration intervaloAging = Duration.ofMinutes(5);

    public ProcesadorPedidos(PedidoService pedidos, MotorSimulacion motor, GestorRecursos recursos, InventarioService inventario) {
        this.pedidos = Objects.requireNonNull(pedidos, "Pedidos");
        this.motor = Objects.requireNonNull(motor, "Motor");
        this.recursos = Objects.requireNonNull(recursos, "Recursos");
        this.inventario = Objects.requireNonNull(inventario, "Inventario");
        motor.registrarAvisos(aviso);
        recursos.registrarAvisos(aviso);
        inventario.registrarAvisos(aviso);
        coordinador = new Thread(this::coordinar, "redxela-coordinador-pedidos");
        coordinador.setDaemon(true);
        coordinador.start();
    }

    /** Registra un pedido existente una sola vez en la corrida actual. */
    public Future<Progreso> procesar(UUID id) {
        Pedido pedido = pedidos.buscar(id);
        var estadoCorrida = motor.estadoCorrida();
        if (estadoCorrida.estado() != EstadoSimulacion.EJECUTANDO) throw new IllegalStateException("Motor no está ejecutando");
        Ejecucion ejecucion = new Ejecucion(pedido, estadoCorrida.corrida(), estadoCorrida.tiempoSimulado());
        synchronized (this) {
            if (cerrado) throw new IllegalStateException("Procesador cerrado");
            if (ejecuciones.containsKey(id)) throw new IllegalStateException("Pedido ya registrado");
            ejecuciones.put(id, ejecucion);
        }
        aviso.release();
        avisarExternos();
        return ejecucion.resultado;
    }

    public synchronized boolean admitido(UUID id) { return ejecuciones.containsKey(id); }

    /** Aparta el siguiente despacho de dos pedidos sin repetir sus etapas anteriores. */
    public synchronized void prepararConflicto(UUID a, UUID b) {
        if (Objects.equals(a, b)) throw new IllegalArgumentException("Se necesitan dos pedidos distintos");
        Ejecucion primero = ejecuciones.get(a), segundo = ejecuciones.get(b);
        for (Ejecucion e : new Ejecucion[]{primero, segundo}) {
            if (e == null || e.estado == EstadoPedido.COMPLETADO || e.estado == EstadoPedido.ERROR || e.conflicto
                    || (e.enviado && e.plan.pasos().get(e.completados).etapa() == EtapaPedido.DESPACHO))
                throw new IllegalStateException("Pedido no disponible para preparar su despacho");
            var siguienteDespacho = e.plan.pasos().subList(e.completados, e.plan.pasos().size()).stream()
                    .filter(p -> p.etapa() == EtapaPedido.DESPACHO).findFirst().orElseThrow();
            if (siguienteDespacho.tipo() != TipoMercancia.GENERAL)
                throw new IllegalArgumentException("La demostración usa despachos de mercancía general");
        }
        if (primero.corrida != segundo.corrida) throw new IllegalStateException("Pedidos de distintas corridas");
        primero.conflicto = true; segundo.conflicto = true;
        aviso.release(); avisarExternos();
    }

    public synchronized boolean listoParaConflicto(UUID id) {
        Ejecucion e = ejecuciones.get(id);
        return e != null && e.conflicto && !e.enviado && e.estado == EstadoPedido.ESPERANDO_RECURSOS
                && e.plan.pasos().get(e.completados).etapa() == EtapaPedido.DESPACHO;
    }

    public synchronized PasoTrabajo iniciarDespachoConflicto(UUID id, long ahora) {
        if (!listoParaConflicto(id)) throw new IllegalStateException("Despacho no disponible");
        Ejecucion e = ejecuciones.get(id);
        // El despacho todavía espera sus recursos; el deadlock no es trabajo efectivo.
        e.estado = EstadoPedido.ESPERANDO_RECURSOS; e.enviado = true; e.iniciado = true;
        e.ultimoInstante = ahora;
        avisarExternos();
        return e.plan.pasos().get(e.completados);
    }

    public synchronized void iniciarTrabajoConflicto(UUID id, long ahora) {
        Ejecucion e = ejecuciones.get(id);
        if (e == null || !e.conflicto || e.estado != EstadoPedido.ESPERANDO_RECURSOS)
            throw new IllegalStateException("Despacho de conflicto no está esperando");
        e.esperaAcumulada = espera(e, ahora);
        e.inicioPaso = ahora; e.ultimoInstante = ahora; e.estado = EstadoPedido.EN_PROCESO;
        avisarExternos();
    }

    /** Confirma únicamente la etapa de despacho ganadora; no vuelve a retirar inventario. */
    public void completarDespachoConflicto(UUID id, long ahora) {
        Ejecucion e;
        Progreso resultado = null;
        synchronized (this) {
            e = ejecuciones.get(id);
            if (e.estado != EstadoPedido.EN_PROCESO || !e.conflicto) throw new IllegalStateException("Despacho no está en conflicto");
            e.procesamientoAcumulado += Math.max(0, ahora - e.inicioPaso);
            e.ultimoInstante = ahora; e.completados++; e.conflicto = false; e.enviado = false; e.iniciado = false;
            e.estado = e.completados == e.plan.pasos().size() ? EstadoPedido.COMPLETADO : EstadoPedido.ESPERANDO_RECURSOS;
            if (e.estado == EstadoPedido.ESPERANDO_RECURSOS) e.esperaDesde = ahora;
            else resultado = vista(e, ahora);
        }
        if (resultado != null) e.resultado.complete(resultado);
        aviso.release(); avisarExternos();
    }

    public void devolverDespachoConflicto(UUID id, long ahora) {
        synchronized (this) {
            Ejecucion e = ejecuciones.get(id);
            if (e == null || e.estado == EstadoPedido.ERROR || e.estado == EstadoPedido.COMPLETADO) return;
            if (e.estado == EstadoPedido.EN_PROCESO && e.conflicto) {
                e.estado = EstadoPedido.ESPERANDO_RECURSOS; e.esperaDesde = ahora;
            }
            e.conflicto = false; e.enviado = false; e.iniciado = false; e.versionBloqueada = -1;
        }
        aviso.release(); avisarExternos();
    }

    /** La víctima vuelve a la cola, pero espera a que termine la operación ganadora. */
    public void esperarDespachoConflicto(UUID id, long ahora) {
        synchronized (this) {
            Ejecucion e = ejecuciones.get(id);
            if (e == null || e.estado != EstadoPedido.ESPERANDO_RECURSOS || !e.conflicto)
                throw new IllegalStateException("Operación de conflicto no disponible");
            e.enviado = false; e.iniciado = false;
        }
        aviso.release(); avisarExternos();
    }

    public void cancelarDespachoConflicto(UUID id, String motivo) {
        Ejecucion e;
        synchronized (this) {
            e = ejecuciones.get(id);
            if (e == null || e.estado == EstadoPedido.COMPLETADO || !e.conflicto) return;
            marcarError(e, e.ultimoInstante, motivo);
            e.conflicto = false; e.enviado = false; e.iniciado = false;
            inventario.cancelarReserva(id);
        }
        e.resultado.completeExceptionally(new IllegalStateException(motivo));
        aviso.release(); avisarExternos();
    }

    public Progreso consultar(UUID id) {
        var estadoCorrida = motor.estadoCorrida();
        synchronized (this) {
            Ejecucion e = ejecuciones.get(id);
            if (e == null) throw new IllegalArgumentException("Pedido no registrado: " + id);
            // El reloj de una nueva corrida no se mezcla con los tiempos de otra.
            long ahora = e.corrida == estadoCorrida.corrida() ? estadoCorrida.tiempoSimulado() : e.ultimoInstante;
            return vista(e, ahora);
        }
    }

    public synchronized PoliticaPlanificacion politica() { return politica; }

    public void cambiarPolitica(PoliticaPlanificacion nueva) {
        synchronized (this) { politica = Objects.requireNonNull(nueva, "Política"); }
        aviso.release();
    }

    public synchronized Duration intervaloAging() { return intervaloAging; }

    public void cambiarIntervaloAging(Duration intervalo) {
        PlanificadorPedidos.intervaloNanos(intervalo);
        synchronized (this) { intervaloAging = intervalo; }
        aviso.release();
    }

    /** Orden de preferencia completo, incluido quien espera recursos; no adquiere permisos. */
    public List<EntradaCola> consultarCola() {
        var estadoCorrida = motor.estadoCorrida();
        long version = recursos.version();
        synchronized (this) {
            long ahora = estadoCorrida.tiempoSimulado();
            var comparador = PlanificadorPedidos.comparador(politica, intervaloAging);
            var ordenados = ejecuciones.values().stream()
                    .filter(e -> e.corrida == estadoCorrida.corrida() && (e.estado == EstadoPedido.ESPERANDO_RECURSOS || e.estado == EstadoPedido.ESPERANDO_INVENTARIO))
                    .sorted((a, b) -> comparador.compare(candidato(a, ahora), candidato(b, ahora))).toList();
            var resultado = new ArrayList<EntradaCola>();
            for (int i = 0; i < ordenados.size(); i++) {
                Ejecucion e = ordenados.get(i);
                var c = candidato(e, ahora);
                String motivo = e.estado == EstadoPedido.ESPERANDO_INVENTARIO ? "Inventario insuficiente"
                        : e.conflicto ? "Esperando la recuperación del despacho" : e.enviado ? "Admitido; pendiente de inicio o recursos"
                        : e.versionBloqueada == version ? "Recursos insuficientes"
                        : "Esperando turno o trabajador";
                Long prioridad = politica == PoliticaPlanificacion.AGING
                        ? PlanificadorPedidos.prioridadEfectiva(c, intervaloAging) : null;
                resultado.add(new EntradaCola(e.pedido.id(), e.pedido.nivelServicio(), e.pedido.unidadesTotales(),
                        i + 1, e.estado, Duration.ofNanos(c.esperaNanos()), prioridad, motivo));
            }
            return List.copyOf(resultado);
        }
    }

    public record EntradaCola(UUID pedidoId, NivelServicio nivelServicio, long unidades, int orden,
                              EstadoPedido estado, Duration esperaAcumulada, Long prioridadEfectiva, String motivo) { }

    /** Duración en tiempo simulado: un segundo base y 100 ms por unidad. */
    public static Duration duracion(PasoTrabajo paso) {
        return Duration.ofMillis(Math.addExact(1000, Math.multiplyExact(paso.unidades(), 100)));
    }

    private void coordinar() {
        try {
            while (!cerrado) {
                aviso.acquire();
                aviso.drainPermits();
                despachar();
            }
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
        }
    }

    private void despachar() {
        while (!cerrado) {
            var estadoCorrida = motor.estadoCorrida();
            EstadoSimulacion estadoMotor = estadoCorrida.estado();
            long corrida = estadoCorrida.corrida();
            long ahora = estadoCorrida.tiempoSimulado();
            long version = recursos.version();
            Ejecucion siguiente;
            var cancelados = new ArrayList<CompletableFuture<Progreso>>();
            synchronized (this) {
                var comparador = PlanificadorPedidos.comparador(politica, intervaloAging);
                for (Ejecucion e : ejecuciones.values()) {
                    if (e.estado != EstadoPedido.COMPLETADO && e.estado != EstadoPedido.ERROR
                            && (e.corrida < corrida || (e.corrida == corrida &&
                            (estadoMotor == EstadoSimulacion.DETENIENDO || estadoMotor == EstadoSimulacion.DETENIDA)))) {
                        marcarError(e, e.corrida == corrida ? ahora : e.ultimoInstante, "Simulación detenida");
                        inventario.cancelarReserva(e.pedido.id());
                        recursos.cancelarEspera(e.operacion());
                        cancelados.add(e.resultado);
                    }
                }
                // Se prueban reservas completas en el orden de la política; los pedidos sin stock no bloquean a otros.
                if (estadoMotor == EstadoSimulacion.EJECUTANDO) {
                    ejecuciones.values().stream().filter(e -> e.corrida == corrida && e.estado == EstadoPedido.ESPERANDO_INVENTARIO)
                            .sorted((a, b) -> comparador.compare(candidato(a, ahora), candidato(b, ahora)))
                            .forEach(e -> { if (inventario.reservar(e.pedido)) e.estado = EstadoPedido.ESPERANDO_RECURSOS; });
                }
                siguiente = estadoMotor != EstadoSimulacion.EJECUTANDO ? null : ejecuciones.values().stream()
                        .filter(e -> e.corrida == corrida && e.estado == EstadoPedido.ESPERANDO_RECURSOS && !e.enviado
                                && !(e.conflicto && e.plan.pasos().get(e.completados).etapa() == EtapaPedido.DESPACHO)
                                && e.versionBloqueada != version)
                        .min((a, b) -> comparador.compare(candidato(a, ahora), candidato(b, ahora))).orElse(null);
                if (siguiente != null) siguiente.enviado = true;
            }
            cancelados.forEach(f -> f.completeExceptionally(new IllegalStateException("Simulación detenida")));
            if (siguiente == null) return;
            try {
                motor.enviarTrabajo(siguiente.corrida, contexto -> ejecutar(siguiente, contexto));
            } catch (RejectedExecutionException | IllegalStateException noDisponible) {
                synchronized (this) { siguiente.enviado = false; }
                return; // El motor avisa cuando termine un trabajador o cambie su estado.
            }
        }
    }

    private void ejecutar(Ejecucion e, MotorSimulacion.ContextoTrabajo contexto) throws InterruptedException {
        synchronized (this) { e.iniciado = true; }
        String operacion = e.operacion();
        var asignacion = new GestorRecursos.Asignacion[1];
        try {
            contexto.avanzar(() -> {
                long version = recursos.version();
                PasoTrabajo paso = e.plan.pasos().get(e.completados);
                var obtenida = recursos.intentarAsignar(operacion, paso.requisitos());
                synchronized (this) {
                    if (obtenida.isEmpty()) {
                        e.versionBloqueada = version;
                    } else {
                        asignacion[0] = obtenida.orElseThrow();
                        e.asignados = asignacion[0].recursos();
                        long ahora = motor.tiempoActual();
                        e.esperaAcumulada = espera(e, ahora);
                        if (e.primeraAtencion < 0) e.primeraAtencion = ahora - e.llegada;
                        e.inicioPaso = ahora;
                        e.ultimoInstante = ahora;
                        e.estado = EstadoPedido.EN_PROCESO;
                        avisarExternos();
                    }
                }
            });
            if (asignacion[0] == null) return;
            PasoTrabajo paso = e.plan.pasos().get(e.completados);
            contexto.esperar(duracion(paso));
            contexto.avanzar(() -> {
                long ahora = motor.tiempoActual();
                synchronized (this) {
                    if (paso.etapa() == EtapaPedido.RETIRO) inventario.retirar(e.pedido.id(), paso.tipo());
                    e.procesamientoAcumulado += Math.max(0, ahora - e.inicioPaso);
                    e.ultimoInstante = ahora;
                    e.completados++;
                    e.estado = e.completados == e.plan.pasos().size()
                            ? EstadoPedido.COMPLETADO : EstadoPedido.ESPERANDO_RECURSOS;
                    if (e.estado == EstadoPedido.ESPERANDO_RECURSOS) e.esperaDesde = ahora;
                    e.versionBloqueada = -1;
                }
            });
        } catch (InterruptedException interrumpido) {
            long ahora = motor.tiempoActual();
            synchronized (this) { marcarError(e, ahora, "Trabajo interrumpido"); }
            throw interrumpido;
        } catch (RuntimeException fallo) {
            long ahora = motor.tiempoActual();
            synchronized (this) { marcarError(e, ahora, fallo.getMessage()); }
        } finally {
            if (asignacion[0] != null) asignacion[0].close();
            boolean fallo;
            synchronized (this) { fallo = e.estado == EstadoPedido.ERROR; }
            if (asignacion[0] != null || fallo) recursos.cancelarEspera(operacion);
            if (fallo) inventario.cancelarReserva(e.pedido.id());
            long ahora = motor.tiempoActual();
            Progreso completado = null;
            String error = null;
            synchronized (this) {
                e.asignados = List.of();
                e.enviado = false;
                e.iniciado = false;
                if (e.estado == EstadoPedido.COMPLETADO) completado = vista(e, ahora);
                if (e.estado == EstadoPedido.ERROR) error = e.error == null ? "Trabajo interrumpido" : e.error;
            }
            if (completado != null) e.resultado.complete(completado);
            if (error != null) e.resultado.completeExceptionally(new IllegalStateException(error));
            aviso.release();
            avisarExternos();
        }
    }

    private Progreso vista(Ejecucion e, long ahora) {
        PasoTrabajo actual = e.completados < e.plan.pasos().size() ? e.plan.pasos().get(e.completados) : null;
        Duration restante = null;
        if (e.estado == EstadoPedido.EN_PROCESO && actual != null) {
            long nanos = 0;
            for (int i = e.completados; i < e.plan.pasos().size(); i++) {
                nanos = Math.addExact(nanos, duracion(e.plan.pasos().get(i)).toNanos());
            }
            nanos = Math.max(0, nanos - Math.max(0, ahora - e.inicioPaso));
            restante = Duration.ofNanos(nanos);
        }
        return new Progreso(e.pedido.id(), e.estado, actual == null ? null : actual.etapa(), e.completados,
                e.plan.pasos().size(), 100.0 * e.completados / e.plan.pasos().size(), e.asignados, restante, e.error,
                e.primeraAtencion < 0 ? null : Duration.ofNanos(e.primeraAtencion),
                Duration.ofNanos(espera(e, ahora)),
                Duration.ofNanos(e.procesamientoAcumulado + (e.estado == EstadoPedido.EN_PROCESO
                        ? Math.max(0, ahora - e.inicioPaso) : 0)));
    }

    public record Progreso(UUID pedidoId, EstadoPedido estado, EtapaPedido etapa, int pasosCompletados,
                           int pasosTotales, double porcentaje, List<String> recursos,
                           Duration restanteEstimado, String error, Duration primeraAtencion,
                           Duration esperaAcumulada, Duration procesamientoAcumulado) { }

    /** Copia breve bajo el monitor; los cálculos estadísticos se realizan fuera de él. */
    public synchronized List<DatoMetrica> datosMetricas(long corrida, long ahora) {
        return ejecuciones.values().stream().filter(e -> e.corrida == corrida).map(e -> new DatoMetrica(
                e.pedido.id(), e.pedido.nivelServicio(), e.estado,
                e.primeraAtencion < 0 ? null : Duration.ofNanos(e.primeraAtencion),
                Duration.ofNanos(espera(e, ahora)),
                Duration.ofNanos(e.procesamientoAcumulado + (e.estado == EstadoPedido.EN_PROCESO
                        ? Math.max(0, ahora - e.inicioPaso) : 0)),
                e.estado == EstadoPedido.COMPLETADO ? Duration.ofNanos(Math.max(0, e.ultimoInstante - e.llegada)) : null,
                Duration.ofNanos(Math.max(0, ahora - e.llegada)))).toList();
    }

    public record DatoMetrica(UUID pedidoId, NivelServicio nivel, EstadoPedido estado, Duration primeraAtencion,
                              Duration espera, Duration procesamiento, Duration total, Duration desdeLlegada) { }

    private static long espera(Ejecucion e, long ahora) {
        return e.esperaAcumulada + (e.estado == EstadoPedido.ESPERANDO_RECURSOS || e.estado == EstadoPedido.ESPERANDO_INVENTARIO
                ? Math.max(0, ahora - e.esperaDesde) : 0);
    }

    private static PlanificadorPedidos.Candidato candidato(Ejecucion e, long ahora) {
        return new PlanificadorPedidos.Candidato(e.pedido, espera(e, ahora));
    }

    private static void marcarError(Ejecucion e, long ahora, String mensaje) {
        if (e.estado == EstadoPedido.ERROR || e.estado == EstadoPedido.COMPLETADO) return;
        e.esperaAcumulada = espera(e, ahora);
        if (e.estado == EstadoPedido.EN_PROCESO) e.procesamientoAcumulado += Math.max(0, ahora - e.inicioPaso);
        e.ultimoInstante = ahora;
        e.estado = EstadoPedido.ERROR;
        e.error = mensaje;
    }

    private static final class Ejecucion {
        final Pedido pedido;
        final PlanTrabajo plan;
        final long corrida;
        final CompletableFuture<Progreso> resultado = new CompletableFuture<>();
        EstadoPedido estado = EstadoPedido.ESPERANDO_INVENTARIO;
        int completados;
        boolean enviado;
        boolean iniciado;
        boolean conflicto;
        long versionBloqueada = -1;
        long inicioPaso;
        final long llegada;
        long esperaDesde;
        long esperaAcumulada;
        long procesamientoAcumulado;
        long primeraAtencion = -1;
        long ultimoInstante;
        List<String> asignados = List.of();
        String error;
        Ejecucion(Pedido pedido, long corrida, long llegada) {
            this.pedido = pedido; this.corrida = corrida; this.plan = PlanTrabajo.paraPedido(pedido);
            this.llegada = llegada; this.esperaDesde = llegada; this.ultimoInstante = llegada;
        }
        String operacion() { return pedido.id() + ":" + completados; }
    }

    @Override public void close() {
        cerrado = true;
        coordinador.interrupt();
        try {
            motor.detener();
            coordinador.join(5000);
            if (coordinador.isAlive()) throw new IllegalStateException("Coordinador no terminó");
            var estadoCorrida = motor.estadoCorrida();
            List<Ejecucion> pendientes;
            synchronized (this) {
                pendientes = ejecuciones.values().stream().filter(e -> !e.resultado.isDone()).toList();
                pendientes.forEach(e -> {
                    marcarError(e, e.corrida == estadoCorrida.corrida() ? estadoCorrida.tiempoSimulado() : e.ultimoInstante,
                            "Procesador cerrado");
                    recursos.cancelarEspera(e.operacion());
                    inventario.cancelarReserva(e.pedido.id());
                });
            }
            pendientes.forEach(e -> e.resultado.completeExceptionally(new IllegalStateException("Procesador cerrado")));
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cierre interrumpido", interrumpido);
        } finally {
            motor.retirarAvisos(aviso);
            recursos.retirarAvisos(aviso);
            inventario.retirarAvisos(aviso);
        }
    }
}

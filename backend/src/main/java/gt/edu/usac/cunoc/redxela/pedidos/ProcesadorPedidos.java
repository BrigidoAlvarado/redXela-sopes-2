package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/** Coordinador FCFS provisional. Los pedidos pendientes no ocupan trabajadores. */
public final class ProcesadorPedidos implements AutoCloseable {
    private final PedidoService pedidos;
    private final MotorSimulacion motor;
    private final GestorRecursos recursos;
    private final Map<UUID, Ejecucion> ejecuciones = new LinkedHashMap<>();
    private final Semaphore aviso = new Semaphore(0);
    private final Thread coordinador;
    private volatile boolean cerrado;

    public ProcesadorPedidos(PedidoService pedidos, MotorSimulacion motor, GestorRecursos recursos) {
        this.pedidos = Objects.requireNonNull(pedidos, "Pedidos");
        this.motor = Objects.requireNonNull(motor, "Motor");
        this.recursos = Objects.requireNonNull(recursos, "Recursos");
        motor.registrarAvisos(aviso);
        recursos.registrarAvisos(aviso);
        coordinador = new Thread(this::coordinar, "redxela-coordinador-pedidos");
        coordinador.setDaemon(true);
        coordinador.start();
    }

    /** Registra un pedido existente una sola vez en la corrida actual. */
    public Future<Progreso> procesar(UUID id) {
        Pedido pedido = pedidos.buscar(id);
        var estadoCorrida = motor.estadoCorrida();
        if (estadoCorrida.estado() != EstadoSimulacion.EJECUTANDO) throw new IllegalStateException("Motor no está ejecutando");
        Ejecucion ejecucion = new Ejecucion(pedido, estadoCorrida.corrida());
        synchronized (this) {
            if (cerrado) throw new IllegalStateException("Procesador cerrado");
            if (ejecuciones.containsKey(id)) throw new IllegalStateException("Pedido ya registrado");
            ejecuciones.put(id, ejecucion);
        }
        aviso.release();
        return ejecucion.resultado;
    }

    public Progreso consultar(UUID id) {
        long ahora = motor.tiempoActual();
        synchronized (this) {
            Ejecucion e = ejecuciones.get(id);
            if (e == null) throw new IllegalArgumentException("Pedido no registrado: " + id);
            return vista(e, ahora);
        }
    }

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
            long version = recursos.version();
            Ejecucion siguiente;
            var cancelados = new ArrayList<CompletableFuture<Progreso>>();
            synchronized (this) {
                for (Ejecucion e : ejecuciones.values()) {
                    if (e.estado != EstadoPedido.COMPLETADO && e.estado != EstadoPedido.ERROR
                            && (e.corrida < corrida || (e.corrida == corrida &&
                            (estadoMotor == EstadoSimulacion.DETENIENDO || estadoMotor == EstadoSimulacion.DETENIDA)))) {
                        e.estado = EstadoPedido.ERROR;
                        e.error = "Simulación detenida";
                        recursos.cancelarEspera(e.operacion());
                        if (!e.iniciado) cancelados.add(e.resultado);
                    }
                }
                siguiente = estadoMotor != EstadoSimulacion.EJECUTANDO ? null : ejecuciones.values().stream()
                        .filter(e -> e.corrida == corrida && e.estado == EstadoPedido.ESPERANDO_RECURSOS && !e.enviado
                                && e.versionBloqueada != version)
                        .min(Comparator.comparingLong(e -> e.pedido.secuenciaEntrada())).orElse(null);
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
                        e.inicioPaso = motor.tiempoActual();
                        e.estado = EstadoPedido.EN_PROCESO;
                    }
                }
            });
            if (asignacion[0] == null) return;
            PasoTrabajo paso = e.plan.pasos().get(e.completados);
            contexto.esperar(duracion(paso));
            contexto.avanzar(() -> {
                synchronized (this) {
                    e.completados++;
                    e.estado = e.completados == e.plan.pasos().size()
                            ? EstadoPedido.COMPLETADO : EstadoPedido.ESPERANDO_RECURSOS;
                    e.versionBloqueada = -1;
                }
            });
        } catch (InterruptedException interrumpido) {
            synchronized (this) { e.estado = EstadoPedido.ERROR; e.error = "Trabajo interrumpido"; }
            throw interrumpido;
        } catch (RuntimeException fallo) {
            synchronized (this) { e.estado = EstadoPedido.ERROR; e.error = fallo.getMessage(); }
        } finally {
            if (asignacion[0] != null) asignacion[0].close();
            boolean fallo;
            synchronized (this) { fallo = e.estado == EstadoPedido.ERROR; }
            if (asignacion[0] != null || fallo) recursos.cancelarEspera(operacion);
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
                e.plan.pasos().size(), 100.0 * e.completados / e.plan.pasos().size(), e.asignados, restante, e.error);
    }

    public record Progreso(UUID pedidoId, EstadoPedido estado, EtapaPedido etapa, int pasosCompletados,
                           int pasosTotales, double porcentaje, List<String> recursos,
                           Duration restanteEstimado, String error) { }

    private static final class Ejecucion {
        final Pedido pedido;
        final PlanTrabajo plan;
        final long corrida;
        final CompletableFuture<Progreso> resultado = new CompletableFuture<>();
        EstadoPedido estado = EstadoPedido.ESPERANDO_RECURSOS;
        int completados;
        boolean enviado;
        boolean iniciado;
        long versionBloqueada = -1;
        long inicioPaso;
        List<String> asignados = List.of();
        String error;
        Ejecucion(Pedido pedido, long corrida) {
            this.pedido = pedido; this.corrida = corrida; this.plan = PlanTrabajo.paraPedido(pedido);
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
            List<Ejecucion> pendientes;
            synchronized (this) {
                pendientes = ejecuciones.values().stream().filter(e -> !e.resultado.isDone()).toList();
                pendientes.forEach(e -> { e.estado = EstadoPedido.ERROR; recursos.cancelarEspera(e.operacion()); });
            }
            pendientes.forEach(e -> e.resultado.completeExceptionally(new IllegalStateException("Procesador cerrado")));
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cierre interrumpido", interrumpido);
        } finally {
            motor.retirarAvisos(aviso);
            recursos.retirarAvisos(aviso);
        }
    }
}

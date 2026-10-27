package gt.edu.usac.cunoc.redxela.recepcion;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;
import gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Recepción produce mensajes; un consumidor independiente los incorpora al inventario. */
public final class RecepcionService implements AutoCloseable {
    public enum Estado { ESPERANDO_ESCANER, ESCANEANDO, EN_COLA, PENDIENTE_ESPACIO, ALMACENADA, ERROR }
    public record IngresoMercaderia(UUID id, String producto, int cantidad) { }
    public record Vista(IngresoMercaderia ingreso, Estado estado, String detalle) { }
    private static final class Registro {
        final IngresoMercaderia ingreso;
        long corrida;
        Estado estado = Estado.ESPERANDO_ESCANER;
        String detalle;
        long versionBloqueada = -1;
        Registro(IngresoMercaderia ingreso, long corrida) { this.ingreso = ingreso; this.corrida = corrida; }
    }
    private final CatalogoProductos catalogo;
    private final InventarioService inventario;
    private final GestorRecursos recursos;
    private final MotorSimulacion motor;
    private final int capacidad;
    private final BlockingQueue<IngresoMercaderia> cola;
    private final Map<UUID, Registro> registros = new LinkedHashMap<>();
    private final Map<UUID, Future<?>> trabajos = new HashMap<>();
    private final Semaphore avisoRecepcion = new Semaphore(0), avisoInventario = new Semaphore(0);
    private final CopyOnWriteArraySet<Semaphore> avisos = new CopyOnWriteArraySet<>();
    private final Thread productor, consumidor;
    private volatile boolean cerrado;
    public void registrarAvisos(Semaphore aviso) { avisos.add(Objects.requireNonNull(aviso)); }
    public void retirarAvisos(Semaphore aviso) { avisos.remove(aviso); }
    private void avisar() { avisos.forEach(Semaphore::release); }

    public RecepcionService(CatalogoProductos catalogo, InventarioService inventario, GestorRecursos recursos,
                            MotorSimulacion motor, int capacidad) {
        if (capacidad < 1) throw new IllegalArgumentException("Capacidad inválida");
        this.catalogo = Objects.requireNonNull(catalogo); this.inventario = Objects.requireNonNull(inventario);
        this.recursos = Objects.requireNonNull(recursos); this.motor = Objects.requireNonNull(motor);
        this.capacidad = capacidad; this.cola = new ArrayBlockingQueue<>(capacidad);
        motor.registrarAvisos(avisoRecepcion); recursos.registrarAvisos(avisoRecepcion);
        inventario.registrarAvisos(avisoInventario);
        productor = new Thread(this::producir, "redxela-recepcion");
        consumidor = new Thread(this::consumir, "redxela-control-inventario");
        productor.setDaemon(true); consumidor.setDaemon(true); productor.start(); consumidor.start();
    }

    /** El límite incluye escaneo, canal y lotes pendientes de espacio. */
    public UUID registrar(String codigo, int cantidad) {
        var producto = catalogo.buscar(codigo);
        if (cantidad < 1 || cantidad > (10 / producto.volumenPorUnidad()) * 100)
            throw new IllegalArgumentException("Cantidad imposible para el almacén");
        var estado = motor.estadoCorrida();
        synchronized (this) {
            if (cerrado || estado.estado() != EstadoSimulacion.EJECUTANDO) throw new IllegalStateException("Recepción no disponible");
            if (registros.values().stream().filter(r -> !terminal(r.estado)).count() >= capacidad)
                throw new RejectedExecutionException("Capacidad de recepciones agotada");
            var ingreso = new IngresoMercaderia(UUID.randomUUID(), producto.id(), cantidad);
            registros.put(ingreso.id(), new Registro(ingreso, estado.corrida()));
            avisar();
            avisoRecepcion.release();
            return ingreso.id();
        }
    }
    public synchronized Vista consultar(UUID id) {
        Registro r = registros.get(id);
        if (r == null) throw new IllegalArgumentException("Recepción desconocida");
        return new Vista(r.ingreso, r.estado, r.detalle);
    }
    private static boolean terminal(Estado e) { return e == Estado.ALMACENADA || e == Estado.ERROR; }

    private void producir() {
        try {
            while (!cerrado) {
                avisoRecepcion.acquire(); avisoRecepcion.drainPermits();
                while (!cerrado) {
                    var corrida = motor.estadoCorrida();
                    long version = recursos.version();
                    Registro r;
                    synchronized (this) {
                        for (Registro registro : registros.values()) {
                            if ((registro.estado == Estado.ESPERANDO_ESCANER || registro.estado == Estado.ESCANEANDO)
                                    && (registro.corrida != corrida.corrida() || corrida.estado() == EstadoSimulacion.DETENIDA
                                    || corrida.estado() == EstadoSimulacion.DETENIENDO)) {
                                registro.estado = Estado.ESPERANDO_ESCANER;
                                registro.detalle = "Pendiente de escaneo; simulación detenida o reiniciada";
                                registro.versionBloqueada = -1;
                                registro.corrida = corrida.corrida();
                                recursos.cancelarEspera("recepcion:" + registro.ingreso.id());
                                avisar();
                            }
                        }
                        r = corrida.estado() != EstadoSimulacion.EJECUTANDO ? null : registros.values().stream()
                                .filter(x -> x.estado == Estado.ESPERANDO_ESCANER && x.versionBloqueada != version)
                                .findFirst().orElse(null);
                        if (r != null) r.estado = Estado.ESCANEANDO;
                    }
                    if (r == null) break;
                    try {
                        Future<?> trabajo = motor.enviarTrabajo(r.corrida, contexto -> escanear(r, contexto));
                        synchronized (this) {
                            if (cerrado) trabajo.cancel(true);
                            else trabajos.put(r.ingreso.id(), trabajo);
                        }
                    }
                    catch (RejectedExecutionException | IllegalStateException ex) {
                        synchronized (this) { if (r.estado == Estado.ESCANEANDO) r.estado = Estado.ESPERANDO_ESCANER; }
                        break;
                    }
                }
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private void escanear(Registro r, MotorSimulacion.ContextoTrabajo contexto) throws InterruptedException {
        String operacion = "recepcion:" + r.ingreso.id();
        GestorRecursos.Asignacion[] asignacion = new GestorRecursos.Asignacion[1];
        try {
            contexto.avanzar(() -> {
                long version = recursos.version();
                var resultado = recursos.intentarAsignar(operacion, new RequisitosRecursos(Map.of(TipoRecurso.ESCANER, 1)));
                synchronized (this) {
                    if (resultado.isEmpty()) {
                        if (!cerrado) { r.versionBloqueada = version; r.estado = Estado.ESPERANDO_ESCANER; }
                    } else asignacion[0] = resultado.orElseThrow();
                    avisar();
                }
            });
            if (asignacion[0] == null) return;
            contexto.esperar(Duration.ofSeconds(1));
            // El escáner se libera antes de publicar; nunca se retiene esperando el canal.
            asignacion[0].close();
            asignacion[0] = null;
            recursos.cancelarEspera(operacion);
            contexto.avanzar(() -> {
                // El crédito de admisión garantiza espacio para cada mensaje aceptado.
                synchronized (this) {
                    if (!cerrado && r.estado == Estado.ESCANEANDO) {
                        if (!cola.offer(r.ingreso)) throw new IllegalStateException("Cola sin capacidad pese al crédito");
                        r.estado = Estado.EN_COLA;
                        avisar();
                        avisoInventario.release();
                    }
                }
            });
        } catch (InterruptedException ex) {
            synchronized (this) {
                if (!cerrado) { r.estado = Estado.ESPERANDO_ESCANER; r.detalle = "Escaneo interrumpido; pendiente de reintento"; r.versionBloqueada = -1; }
                else { r.estado = Estado.ERROR; r.detalle = "Recepción cerrada"; }
            }
            avisar();
            throw ex;
        } catch (RuntimeException ex) {
            synchronized (this) { r.estado = Estado.ERROR; r.detalle = ex.toString(); }
            avisar();
        } finally {
            if (asignacion[0] != null) asignacion[0].close();
            boolean error;
            synchronized (this) { error = r.estado == Estado.ERROR; }
            if (asignacion[0] != null || error) recursos.cancelarEspera(operacion);
            avisoRecepcion.release();
        }
    }

    private void consumir() {
        try {
            while (!cerrado) {
                avisoInventario.acquire(); avisoInventario.drainPermits();
                IngresoMercaderia mensaje;
                while ((mensaje = cola.poll()) != null) {
                    synchronized (this) { Registro r = registros.get(mensaje.id()); if (r.estado == Estado.EN_COLA) r.estado = Estado.PENDIENTE_ESPACIO; }
                }
                List<Registro> pendientes;
                synchronized (this) { pendientes = registros.values().stream().filter(r -> r.estado == Estado.PENDIENTE_ESPACIO).toList(); }
                for (Registro r : pendientes) {
                    try {
                        boolean almacenada = inventario.almacenar(r.ingreso.id(), r.ingreso.producto(), r.ingreso.cantidad());
                        synchronized (this) { if (almacenada) { r.estado = Estado.ALMACENADA; r.detalle = null; }
                            else r.detalle = "Espacio insuficiente"; }
                        avisar();
                    } catch (RuntimeException ex) {
                        synchronized (this) { r.estado = Estado.ERROR; r.detalle = ex.toString(); }
                        avisar();
                    }
                }
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    @Override public void close() {
        cerrado = true; productor.interrupt(); consumidor.interrupt();
        synchronized (this) { trabajos.values().forEach(f -> f.cancel(true)); }
        try { productor.join(5000); consumidor.join(5000); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Cierre interrumpido", ex); }
        if (productor.isAlive() || consumidor.isAlive()) throw new IllegalStateException("Áreas no terminaron");
        synchronized (this) { registros.values().stream().filter(r -> !terminal(r.estado)).forEach(r -> {
            r.estado = Estado.ERROR; r.detalle = "Recepción cerrada"; recursos.cancelarEspera("recepcion:" + r.ingreso.id()); }); }
        motor.retirarAvisos(avisoRecepcion); recursos.retirarAvisos(avisoRecepcion); inventario.retirarAvisos(avisoInventario);
    }
}

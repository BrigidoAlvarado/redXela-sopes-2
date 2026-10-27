package gt.edu.usac.cunoc.redxela.conflicto;

import gt.edu.usac.cunoc.redxela.catalogo.TipoMercancia;
import gt.edu.usac.cunoc.redxela.pedidos.PasoTrabajo;
import gt.edu.usac.cunoc.redxela.pedidos.ProcesadorPedidos;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.recursos.RequisitosRecursos;
import gt.edu.usac.cunoc.redxela.recursos.TipoRecurso;
import gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** Dos despachos reales comparten C01 y S01; el resto del sistema conserva la adquisición completa. */
public final class DemostracionDeadlock implements AutoCloseable {
    public enum Estado { INACTIVA, ESPERANDO_DESPACHOS, EN_CURSO, RECUPERANDO, RESUELTA, CANCELADA }
    public record Operacion(UUID pedido, String id, List<String> posee, String espera) { }
    public record Vista(Estado estado, boolean deadlock, Operacion a, Operacion b, UUID ganador, String explicacion) { }
    private final MotorSimulacion motor;
    private final ProcesadorPedidos procesador;
    private final GestorRecursos recursos;
    private final Semaphore aviso = new Semaphore(0);
    private Estado estado = Estado.INACTIVA;
    private UUID a, b, ganador;
    private long corrida;
    private Thread coordinador, hiloA, hiloB;
    private volatile Future<?> futuroA, futuroB;
    private CountDownLatch primeros, ganadorTerminado, adquiridoGanador;
    private CountDownLatch terminados;
    private volatile boolean cerrada;

    public DemostracionDeadlock(MotorSimulacion motor, ProcesadorPedidos procesador, GestorRecursos recursos) {
        this.motor = Objects.requireNonNull(motor); this.procesador = Objects.requireNonNull(procesador);
        this.recursos = Objects.requireNonNull(recursos);
        motor.registrarAvisos(aviso); procesador.registrarAvisos(aviso); recursos.registrarAvisos(aviso);
    }

    public void iniciar(UUID primero, UUID segundo) {
        var actual = motor.estadoCorrida();
        if (actual.estado() != EstadoSimulacion.EJECUTANDO) throw new IllegalStateException("Motor no está ejecutando");
        synchronized (this) {
            if (actual.corrida() != corrida && estado != Estado.INACTIVA) estado = Estado.CANCELADA;
            if (cerrada || (terminados != null && terminados.getCount() != 0 && actual.corrida() == corrida)
                    || (estado != Estado.INACTIVA && estado != Estado.RESUELTA && estado != Estado.CANCELADA))
                throw new IllegalStateException("Ya existe una demostración activa");
            procesador.prepararConflicto(primero, segundo);
            a = primero; b = segundo; ganador = null; corrida = actual.corrida();
            primeros = new CountDownLatch(2); ganadorTerminado = new CountDownLatch(1);
            adquiridoGanador = new CountDownLatch(1); terminados = null;
            hiloA = null; hiloB = null; futuroA = null; futuroB = null;
            estado = Estado.ESPERANDO_DESPACHOS;
            coordinador = new Thread(this::esperarDespachos, "redxela-coordinador-deadlock");
            coordinador.setDaemon(true); coordinador.start();
        }
        aviso.release();
    }

    private void esperarDespachos() {
        try {
            while (true) {
                aviso.acquire(); aviso.drainPermits();
                var actual = motor.estadoCorrida();
                if (actual.corrida() != corrida || actual.estado() == EstadoSimulacion.DETENIDA
                        || actual.estado() == EstadoSimulacion.DETENIENDO || cerrada) {
                    synchronized (this) { estado = Estado.CANCELADA; }
                    return;
                }
                if (actual.estado() != EstadoSimulacion.EJECUTANDO
                        || !procesador.listoParaConflicto(a) || !procesador.listoParaConflicto(b)) continue;
                PasoTrabajo[] pasos = new PasoTrabajo[2];
                if (!motor.avanzarSiEjecutando(corrida, () -> {
                    long ahora = motor.tiempoActual();
                    pasos[0] = procesador.iniciarDespachoConflicto(a, ahora);
                    pasos[1] = procesador.iniciarDespachoConflicto(b, ahora);
                })) continue;
                // Se admiten pedidos generales: su despacho requiere un encargado, C01 y S01.
                if (pasos[0].tipo() != TipoMercancia.GENERAL || pasos[1].tipo() != TipoMercancia.GENERAL) {
                    procesador.devolverDespachoConflicto(a, motor.tiempoActual());
                    procesador.devolverDespachoConflicto(b, motor.tiempoActual());
                    synchronized (this) { estado = Estado.CANCELADA; }
                    return;
                }
                synchronized (this) { estado = Estado.EN_CURSO; terminados = new CountDownLatch(2); }
                futuroA = motor.enviarTrabajoConflicto(corrida, contexto -> operar(a, "C01", "S01", pasos[0], contexto));
                futuroB = motor.enviarTrabajoConflicto(corrida, contexto -> operar(b, "S01", "C01", pasos[1], contexto));
                return;
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (RuntimeException ex) {
            if (futuroA != null) futuroA.cancel(true);
            if (futuroB != null) futuroB.cancel(true);
            procesador.cancelarDespachoConflicto(a, "Demostración cancelada: " + ex.getMessage());
            procesador.cancelarDespachoConflicto(b, "Demostración cancelada: " + ex.getMessage());
            synchronized (this) { estado = Estado.CANCELADA; }
        }
    }

    private String operacion(UUID id) { return "despacho-conflicto:" + id; }

    private void operar(UUID pedido, String primero, String segundo, PasoTrabajo paso,
                        MotorSimulacion.ContextoTrabajo contexto) throws InterruptedException {
        String operacion = operacion(pedido);
        var propio = new GestorRecursos.Asignacion[1];
        var personal = new GestorRecursos.Asignacion[1];
        GestorRecursos.Asignacion segundoRecurso = null;
        var disponibilidad = new Semaphore(0);
        recursos.registrarAvisos(disponibilidad); motor.registrarAvisos(disponibilidad);
        synchronized (this) { if (pedido.equals(a)) hiloA = Thread.currentThread(); else hiloB = Thread.currentThread(); }
        try {
            while (propio[0] == null) {
                disponibilidad.drainPermits();
                long version = recursos.version();
                contexto.avanzar(() -> {
                    boolean libre = recursos.estado().stream().anyMatch(u -> u.id().equals(primero) && u.propietario() == null);
                    if (!libre || recursos.disponibles(TipoRecurso.ENCARGADO_BODEGA) == 0) return;
                    var primera = recursos.intentarFisico(operacion, primero);
                    if (primera.isEmpty()) return;
                    var encargado = recursos.intentarAsignar(operacion + ":personal",
                            new RequisitosRecursos(Map.of(TipoRecurso.ENCARGADO_BODEGA, 1)));
                    if (encargado.isEmpty()) { primera.orElseThrow().close(); return; }
                    propio[0] = primera.orElseThrow(); personal[0] = encargado.orElseThrow();
                });
                if (propio[0] == null) {
                    while (recursos.version() == version) disponibilidad.acquire();
                }
            }
            primeros.countDown();
            primeros.await();
            contexto.avanzar(() -> recursos.anunciarEsperaFisica(operacion, segundo));
            segundoRecurso = recursos.adquirirFisicoEsperado(operacion, segundo); // Espera real en el semáforo.
            adquiridoGanador.countDown();
            contexto.avanzar(() -> procesador.iniciarTrabajoConflicto(pedido, motor.tiempoActual()));
            contexto.esperar(ProcesadorPedidos.duracion(paso));
            contexto.avanzar(() -> procesador.completarDespachoConflicto(pedido, motor.tiempoActual()));
            synchronized (this) { if (pedido.equals(ganador)) ganadorTerminado.countDown(); }
        } catch (InterruptedException ex) {
            boolean victima;
            synchronized (this) { victima = ganador != null && !pedido.equals(ganador) && estado == Estado.RECUPERANDO; }
            if (victima) {
                try {
                    // Solo la víctima libera sus permisos. Este paso corto no espera bajo el lock del motor.
                    contexto.avanzar(() -> {
                        if (propio[0] != null) { propio[0].close(); propio[0] = null; }
                        if (personal[0] != null) { personal[0].close(); personal[0] = null; }
                        procesador.esperarDespachoConflicto(pedido, motor.tiempoActual());
                    });
                    // El ganador estaba bloqueado en el semáforo; la reserva impide que otro se adelante.
                    // Esta espera sucede fuera de ContextoTrabajo.avanzar y, por tanto, fuera del lock del motor.
                    adquiridoGanador.await();
                    ganadorTerminado.await();
                    contexto.avanzar(() -> procesador.devolverDespachoConflicto(pedido, motor.tiempoActual()));
                    synchronized (this) { estado = Estado.RESUELTA; }
                    return;
                } catch (InterruptedException detenido) { ex = detenido; }
            }
            procesador.cancelarDespachoConflicto(pedido, "Demostración interrumpida");
            synchronized (this) { estado = Estado.CANCELADA; }
            throw ex;
        } catch (RuntimeException ex) {
            procesador.cancelarDespachoConflicto(pedido, "Error en demostración: " + ex.getMessage());
            Thread otra;
            synchronized (this) {
                estado = Estado.CANCELADA;
                otra = pedido.equals(a) ? hiloB : hiloA;
            }
            if (otra != null) otra.interrupt();
            throw ex;
        } finally {
            adquiridoGanador.countDown(); // También libera a la víctima si el ganador fue cancelado.
            if (segundoRecurso != null) segundoRecurso.close();
            if (propio[0] != null) propio[0].close();
            if (personal[0] != null) personal[0].close();
            recursos.cancelarEspera(operacion); recursos.cancelarEspera(operacion + ":personal");
            recursos.retirarAvisos(disponibilidad); motor.retirarAvisos(disponibilidad);
            aviso.release();
            terminados.countDown();
        }
    }

    public synchronized Vista consultar() {
        var foto = recursos.fotoConflicto();
        String opA = a == null ? null : operacion(a), opB = b == null ? null : operacion(b);
        Operacion oa = vista(a, opA, foto), ob = vista(b, opB, foto);
        boolean ciclo = oa != null && ob != null && oa.posee().contains("C01") && ob.posee().contains("S01")
                && "S01".equals(oa.espera()) && "C01".equals(ob.espera());
        return new Vista(estado, ciclo, oa, ob, ganador,
                ciclo ? "A posee C01 y espera S01 de B; B posee S01 y espera C01 de A." : "Sin espera circular activa");
    }

    private Operacion vista(UUID pedido, String operacion, GestorRecursos.FotoConflicto foto) {
        if (pedido == null) return null;
        return new Operacion(pedido, operacion, foto.unidades().stream().filter(u -> operacion.equals(u.propietario()))
                .map(GestorRecursos.EstadoUnidad::id).toList(), foto.esperas().get(operacion));
    }

    public void resolver(UUID seleccionado) {
        if (motor.estado() != EstadoSimulacion.EJECUTANDO) throw new IllegalStateException("Motor no está ejecutando");
        synchronized (this) {
            if (estado != Estado.EN_CURSO || !consultar().deadlock()) throw new IllegalStateException("No hay deadlock activo");
            if (!Objects.equals(seleccionado, a) && !Objects.equals(seleccionado, b)) throw new IllegalArgumentException("Ganador desconocido");
            recursos.reservarParaEspera(operacion(seleccionado));
            ganador = seleccionado; estado = Estado.RECUPERANDO;
            Thread victima = seleccionado.equals(a) ? hiloB : hiloA;
            victima.interrupt();
        }
    }

    @Override public void close() {
        Thread anterior;
        synchronized (this) {
            cerrada = true; anterior = coordinador;
            if (anterior != null) anterior.interrupt();
        }
        try { if (anterior != null) anterior.join(5000); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        if (anterior != null && anterior.isAlive()) throw new IllegalStateException("Coordinador no terminó");
        Estado estadoAnterior;
        synchronized (this) {
            if (futuroA != null) futuroA.cancel(true);
            if (futuroB != null) futuroB.cancel(true);
            estadoAnterior = estado;
        }
        if (estadoAnterior == Estado.ESPERANDO_DESPACHOS) {
            if (a != null) procesador.devolverDespachoConflicto(a, motor.tiempoActual());
            if (b != null) procesador.devolverDespachoConflicto(b, motor.tiempoActual());
        } else if (estadoAnterior == Estado.EN_CURSO || estadoAnterior == Estado.RECUPERANDO) {
            if (a != null) procesador.cancelarDespachoConflicto(a, "Demostración cerrada");
            if (b != null) procesador.cancelarDespachoConflicto(b, "Demostración cerrada");
        }
        motor.retirarAvisos(aviso); procesador.retirarAvisos(aviso); recursos.retirarAvisos(aviso);
    }
}

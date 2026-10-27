package gt.edu.usac.cunoc.redxela.simulacion;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Ciclo de vida y puerta de progreso. No procesa pedidos ni planifica recursos. */
public final class MotorSimulacion implements AutoCloseable {
    // La cola justa evita que pasos repetidos se adelanten indefinidamente a una pausa.
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition cambio = lock.newCondition();
    private final RelojSimulado reloj;
    private final int trabajadores;
    private final Duration plazoDetencion;
    private EstadoSimulacion estado = EstadoSimulacion.DETENIDA;
    private long corrida;
    private EjecutorTrabajos ejecutor;
    private EjecutorTrabajos ejecutorConflicto;
    private final CopyOnWriteArraySet<Semaphore> avisos = new CopyOnWriteArraySet<>();

    /** La señal solo despierta al coordinador; no ejecuta código dentro del lock del motor. */
    public void registrarAvisos(Semaphore aviso) { avisos.add(Objects.requireNonNull(aviso)); }
    public void retirarAvisos(Semaphore aviso) { avisos.remove(aviso); }
    private void avisar() { avisos.forEach(Semaphore::release); }

    public MotorSimulacion(int trabajadores, double factorTemporal) {
        this(trabajadores, new RelojSimulado(factorTemporal), Duration.ofSeconds(5));
    }

    MotorSimulacion(int trabajadores, RelojSimulado reloj, Duration plazoDetencion) {
        if (trabajadores < 1) {
            throw new IllegalArgumentException("Debe existir al menos un trabajador");
        }
        this.reloj = Objects.requireNonNull(reloj, "Reloj");
        this.plazoDetencion = Objects.requireNonNull(plazoDetencion, "Plazo de detención");
        if (plazoDetencion.isNegative()) {
            throw new IllegalArgumentException("El plazo de detención no puede ser negativo");
        }
        this.trabajadores = trabajadores;
    }

    public void iniciar() {
        lock.lock();
        try {
            exigirEstado(EstadoSimulacion.DETENIDA);
            ejecutor = new EjecutorTrabajos(trabajadores, ++corrida, this::avisar);
            reloj.iniciar();
            estado = EstadoSimulacion.EJECUTANDO;
            avisar();
        } finally {
            lock.unlock();
        }
    }

    public void pausar() {
        lock.lock();
        try {
            exigirEstado(EstadoSimulacion.EJECUTANDO);
            // avanzar comparte este lock: al retornar ya terminó cualquier paso corto previo.
            reloj.pausar();
            estado = EstadoSimulacion.PAUSADA;
            avisar();
            cambio.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void reanudar() {
        lock.lock();
        try {
            exigirEstado(EstadoSimulacion.PAUSADA);
            reloj.reanudar();
            estado = EstadoSimulacion.EJECUTANDO;
            avisar();
            cambio.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Future<Void> enviarTrabajo(TrabajoSimulado trabajo) {
        Objects.requireNonNull(trabajo, "Trabajo");
        lock.lock();
        try {
            exigirEstado(EstadoSimulacion.EJECUTANDO);
            var contexto = new ContextoTrabajo(corrida);
            return ejecutor.enviar(() -> {
                contexto.avanzar(() -> { }); // También las tareas aún no iniciadas respetan la pausa.
                trabajo.ejecutar(contexto);
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    /** La verificación y la admisión pertenecen a la misma sección crítica. */
    public Future<Void> enviarTrabajo(long corridaEsperada, TrabajoSimulado trabajo) {
        lock.lock();
        try {
            if (corrida != corridaEsperada) throw new IllegalStateException("Corrida anterior");
            return enviarTrabajo(trabajo);
        } finally {
            lock.unlock();
        }
    }

    /** Dos hilos reservados únicamente para la demostración, sin ocupar trabajadores de pedidos. */
    public Future<Void> enviarTrabajoConflicto(long corridaEsperada, TrabajoSimulado trabajo) {
        lock.lock();
        try {
            if (corrida != corridaEsperada) throw new IllegalStateException("Corrida anterior");
            exigirEstado(EstadoSimulacion.EJECUTANDO);
            if (ejecutorConflicto == null) ejecutorConflicto = new EjecutorTrabajos(2, corrida, this::avisar);
            var contexto = new ContextoTrabajo(corrida);
            return ejecutorConflicto.enviar(() -> {
                contexto.avanzar(() -> { });
                trabajo.ejecutar(contexto);
                return null;
            });
        } finally { lock.unlock(); }
    }

    /** Espera de un hilo independiente con el mismo reloj y la misma puerta de pausa. */
    public void esperarTiempo(long corridaEsperada, Duration duracion) throws InterruptedException {
        new ContextoTrabajo(corridaEsperada).esperar(duracion);
    }

    public void detener() throws InterruptedException {
        EjecutorTrabajos anterior;
        EjecutorTrabajos conflictoAnterior;
        lock.lock();
        try {
            if (estado == EstadoSimulacion.DETENIDA) {
                return;
            }
            if (ejecutor.esTrabajadorActual() || (ejecutorConflicto != null && ejecutorConflicto.esTrabajadorActual())) {
                throw new IllegalStateException("Un trabajador no puede esperar su propia terminación");
            }
            reloj.pausar();
            estado = EstadoSimulacion.DETENIENDO;
            avisar();
            anterior = ejecutor;
            conflictoAnterior = ejecutorConflicto;
            // Cancela antes de despertar: ningún trabajador puede completar el Future
            // al observar DETENIENDO entre la transición y la solicitud de cancelación.
            try {
                anterior.cancelarYApagar();
                if (conflictoAnterior != null) conflictoAnterior.cancelarYApagar();
            } finally {
                cambio.signalAll();
            }
        } finally {
            lock.unlock();
        }

        // Nunca esperar terminación bajo lock: los trabajadores lo necesitan para salir.
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Controlador de detención interrumpido");
            }
            if (!anterior.esperarTerminacion(plazoDetencion)) {
                throw new IllegalStateException("Hay trabajadores sin terminar; la simulación continúa DETENIENDO");
            }
            if (conflictoAnterior != null && !conflictoAnterior.esperarTerminacion(plazoDetencion)) {
                throw new IllegalStateException("Hay operaciones de conflicto sin terminar");
            }
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw interrumpido;
        }

        lock.lock();
        try {
            // Otro detener concurrente puede haber terminado esta corrida y comenzado otra.
            if (ejecutor == anterior) {
                ejecutor = null;
                ejecutorConflicto = null;
                estado = EstadoSimulacion.DETENIDA;
                avisar();
            }
        } finally {
            lock.unlock();
        }
    }

    public EstadoSimulacion estado() {
        lock.lock();
        try { return estado; } finally { lock.unlock(); }
    }

    /** Estado e identidad se leen juntos para no confundir dos corridas concurrentes. */
    public EstadoCorrida estadoCorrida() {
        lock.lock();
        try { return new EstadoCorrida(estado, corrida, reloj.tiempoActual()); } finally { lock.unlock(); }
    }

    public record EstadoCorrida(EstadoSimulacion estado, long corrida, long tiempoSimulado) { }

    /** El reloj tiene su propio monitor, evitando invertir el orden entre motor y recursos. */
    public java.util.function.Supplier<RelojSimulado.Foto> relojMedicion() { return reloj::foto; }

    /** Obtiene una copia coherente con las transiciones que usan la puerta del motor. */
    public <T> T conEstado(java.util.function.Function<EstadoCorrida, T> lectura) {
        lock.lock();
        try { return lectura.apply(new EstadoCorrida(estado, corrida, reloj.tiempoActual())); }
        finally { lock.unlock(); }
    }

    /** Un paso breve de un servicio independiente comparte la puerta de pausa del motor. */
    public boolean avanzarSiEjecutando(Runnable paso) throws InterruptedException {
        return avanzarSiEjecutando(-1, paso);
    }

    public boolean avanzarSiEjecutando(long corridaEsperada, Runnable paso) throws InterruptedException {
        Objects.requireNonNull(paso, "Paso de progreso");
        lock.lockInterruptibly();
        try {
            if (estado != EstadoSimulacion.EJECUTANDO || (corridaEsperada >= 0 && corrida != corridaEsperada)) return false;
            paso.run();
            return true;
        } finally { lock.unlock(); }
    }

    public long corridaActual() {
        lock.lock();
        try { return corrida; } finally { lock.unlock(); }
    }

    public long tiempoActual() {
        lock.lock();
        try { return reloj.tiempoActual(); } finally { lock.unlock(); }
    }

    public double factorTemporal() {
        lock.lock();
        try { return reloj.factor(); } finally { lock.unlock(); }
    }

    public void cambiarVelocidad(double factor) {
        lock.lock();
        try {
            reloj.cambiarVelocidad(factor);
            cambio.signalAll(); // Las esperas recalculan su duración real con el nuevo factor.
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            detener();
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió el cierre del motor", interrumpido);
        }
    }

    private void exigirEstado(EstadoSimulacion esperado) {
        if (estado != esperado) {
            throw new IllegalStateException("Se esperaba " + esperado + ", estado actual: " + estado);
        }
    }

    /** Contexto ligado a una corrida; no es reutilizable en la siguiente. */
    public final class ContextoTrabajo {
        private final long corridaPropia;

        private ContextoTrabajo(long corridaPropia) { this.corridaPropia = corridaPropia; }

        /** Ejecuta solamente un paso corto de progreso, sin I/O ni esperas dentro del Runnable. */
        public void avanzar(Runnable paso) throws InterruptedException {
            Objects.requireNonNull(paso, "Paso de progreso");
            lock.lockInterruptibly();
            try {
                esperarHabilitacion();
                paso.run();
            } finally {
                lock.unlock();
            }
        }

        /** Espera trabajo simulado sin espera activa, descontando pausas y cambios de velocidad. */
        public void esperar(Duration duracionSimulada) throws InterruptedException {
            Objects.requireNonNull(duracionSimulada, "Duración simulada");
            if (duracionSimulada.isNegative()) {
                throw new IllegalArgumentException("La duración simulada no puede ser negativa");
            }
            long nanos = duracionSimulada.toNanos();
            lock.lockInterruptibly();
            try {
                esperarHabilitacion();
                long objetivo = Math.addExact(reloj.tiempoActual(), nanos);
                while (true) {
                    esperarHabilitacion();
                    long restante = objetivo - reloj.tiempoActual();
                    if (restante <= 0) {
                        return;
                    }
                    long esperaReal = Math.max(1, (long) Math.ceil(restante / reloj.factor()));
                    cambio.awaitNanos(esperaReal);
                }
            } finally {
                lock.unlock();
            }
        }

        private void esperarHabilitacion() throws InterruptedException {
            // await suelta lock atómicamente; al despertar siempre se verifica el predicado.
            while (corridaPropia == corrida && estado == EstadoSimulacion.PAUSADA) {
                cambio.await();
            }
            if (corridaPropia != corrida || estado != EstadoSimulacion.EJECUTANDO
                    || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Trabajo cancelado o perteneciente a una corrida anterior");
            }
        }
    }
}

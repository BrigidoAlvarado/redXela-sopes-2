package gt.edu.usac.cunoc.redxela.simulacion;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Reloj monotónico bajo demanda: no necesita un hilo que incremente un contador. */
public final class RelojSimulado {
    private final LongSupplier fuenteNanos;
    private double factor;
    private double nanosAcumulados;
    private long referenciaReal;
    private boolean avanzando;
    private long corrida;

    public record Foto(long corrida, long tiempo) { }

    public synchronized Foto foto() { return new Foto(corrida, tiempoActual()); }

    public RelojSimulado(double factor) {
        this(factor, System::nanoTime);
    }

    // La única sustitución temporal es una fuente controlable para las pruebas del paquete.
    RelojSimulado(double factor, LongSupplier fuenteNanos) {
        validarFactor(factor);
        this.factor = factor;
        this.fuenteNanos = Objects.requireNonNull(fuenteNanos, "Fuente de nanosegundos");
        this.referenciaReal = fuenteNanos.getAsLong();
    }

    /** Nanosegundos simulados desde iniciar la corrida, no una fecha del calendario. */
    public synchronized long tiempoActual() {
        acumular();
        return (long) nanosAcumulados;
    }

    public synchronized double factor() { return factor; }

    synchronized void iniciar() {
        corrida++;
        nanosAcumulados = 0;
        referenciaReal = fuenteNanos.getAsLong();
        avanzando = true;
    }

    public synchronized void pausar() {
        acumular();
        avanzando = false;
    }

    public synchronized void reanudar() {
        if (!avanzando) {
            referenciaReal = fuenteNanos.getAsLong();
            avanzando = true;
        }
    }

    public synchronized void cambiarVelocidad(double nuevoFactor) {
        validarFactor(nuevoFactor);
        // Se cobra primero el intervalo anterior con su velocidad anterior.
        acumular();
        factor = nuevoFactor;
    }

    private void acumular() {
        long ahora = fuenteNanos.getAsLong();
        if (avanzando) {
            // Se conservan fracciones de nanosegundo entre consultas. El long publicado
            // satura en Long.MAX_VALUE (unos 292 años simulados) y nunca se vuelve negativo.
            nanosAcumulados = Math.min(Long.MAX_VALUE,
                    nanosAcumulados + (ahora - referenciaReal) * factor);
        }
        referenciaReal = ahora;
    }

    private static void validarFactor(double factor) {
        if (!Double.isFinite(factor) || factor <= 0) {
            throw new IllegalArgumentException("El factor temporal debe ser positivo y finito");
        }
    }
}

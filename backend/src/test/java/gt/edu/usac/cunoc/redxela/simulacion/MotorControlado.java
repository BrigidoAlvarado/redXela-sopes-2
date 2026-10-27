package gt.edu.usac.cunoc.redxela.simulacion;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Fuente de tiempo determinista solo para pruebas de integración. */
public final class MotorControlado {
    private final AtomicLong nanos = new AtomicLong();
    private final MotorSimulacion motor = new MotorSimulacion(1, new RelojSimulado(60, nanos::get), Duration.ofSeconds(5));

    public MotorSimulacion motor() { return motor; }
    public void avanzarReal(Duration tiempo) {
        nanos.addAndGet(tiempo.toNanos());
        motor.cambiarVelocidad(60); // Despierta las esperas sin crear un reloj adicional.
    }
}

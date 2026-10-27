package gt.edu.usac.cunoc.redxela.simulacion;

/** Trabajo cooperativo: el progreso usa avanzar y las esperas usan esperar del contexto. */
@FunctionalInterface
public interface TrabajoSimulado {
    void ejecutar(MotorSimulacion.ContextoTrabajo contexto) throws InterruptedException;
}

package gt.edu.usac.cunoc.redxela.simulacion;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Detalle del motor: hilos acotados, sin una segunda cola que oculte trabajo pendiente. */
final class EjecutorTrabajos {
    private final Set<FutureTask<Void>> trabajos = ConcurrentHashMap.newKeySet();
    private final Set<Thread> hilos = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor ejecutor;

    EjecutorTrabajos(int trabajadores, long corrida) {
        var numeroHilo = new AtomicInteger();
        ejecutor = new ThreadPoolExecutor(trabajadores, trabajadores, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), tarea -> {
                    var hilo = new Thread(tarea,
                            "redxela-corrida-" + corrida + "-trabajador-" + numeroHilo.incrementAndGet());
                    hilo.setDaemon(false);
                    hilos.add(hilo);
                    return hilo;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    Future<Void> enviar(Callable<Void> trabajo) {
        var futuro = new FutureTask<Void>(() -> {
            try {
                return trabajo.call();
            } catch (InterruptedException interrumpido) {
                // No se convierte una interrupción en una finalización exitosa.
                Thread.currentThread().interrupt();
                throw interrumpido;
            }
        }) {
            @Override
            protected void done() { trabajos.remove(this); }
        };
        trabajos.add(futuro);
        try {
            ejecutor.execute(futuro);
            return futuro;
        } catch (RuntimeException falloAdmision) {
            futuro.cancel(false);
            throw falloAdmision;
        }
    }

    boolean esTrabajadorActual() { return hilos.contains(Thread.currentThread()); }

    void cancelarYApagar() {
        // Future.cancel(true) pide interrupción; NO demuestra que el trabajador terminó.
        for (var trabajo : List.copyOf(trabajos)) {
            trabajo.cancel(true);
        }
        ejecutor.shutdownNow();
    }

    boolean esperarTerminacion(Duration plazo) throws InterruptedException {
        long nanos = plazo.toNanos();
        long inicio = System.nanoTime(); // Límite de infraestructura, no tiempo del dominio.
        if (!ejecutor.awaitTermination(nanos, TimeUnit.NANOSECONDS)) {
            return false;
        }
        // TERMINATED puede observarse justo antes de que Thread.run retorne.
        // join confirma también la salida física de los hilos dentro del mismo plazo.
        for (Thread hilo : hilos) {
            if (hilo.isAlive()) {
                long restante = nanos - (System.nanoTime() - inicio);
                if (restante <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedJoin(hilo, restante);
                if (hilo.isAlive()) {
                    return false;
                }
            }
        }
        return true;
    }
}

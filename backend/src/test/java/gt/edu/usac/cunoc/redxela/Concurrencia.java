package gt.edu.usac.cunoc.redxela;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arranque simultáneo y cierre acotado; ninguna prueba coordina hilos con sleep. */
public final class Concurrencia {
    private Concurrencia() { }

    public static <T> List<T> ejecutar(int cantidad, IntFunction<T> accion) throws Exception {
        var ejecutor = Executors.newFixedThreadPool(8);
        var inicio = new CountDownLatch(1);
        var listos = new CountDownLatch(Math.min(8, cantidad));
        try {
            List<Future<T>> tareas = new ArrayList<>();
            for (int i = 0; i < cantidad; i++) {
                int indice = i;
                tareas.add(ejecutor.submit(() -> {
                    listos.countDown();
                    inicio.await();
                    return accion.apply(indice);
                }));
            }
            assertTrue(listos.await(10, TimeUnit.SECONDS), "Los trabajadores no llegaron al punto de inicio");
            inicio.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> tarea : tareas) {
                resultados.add(tarea.get(10, TimeUnit.SECONDS));
            }
            return List.copyOf(resultados);
        } finally {
            inicio.countDown();
            ejecutor.shutdownNow();
            assertTrue(ejecutor.awaitTermination(10, TimeUnit.SECONDS), "Quedaron trabajadores activos");
        }
    }
}

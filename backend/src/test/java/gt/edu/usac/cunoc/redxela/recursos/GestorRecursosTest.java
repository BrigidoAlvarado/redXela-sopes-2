package gt.edu.usac.cunoc.redxela.recursos;

import gt.edu.usac.cunoc.redxela.Concurrencia;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static gt.edu.usac.cunoc.redxela.recursos.TipoRecurso.*;

class GestorRecursosTest {
    @Test void capacidadesYUnidadesSonLasDelEnunciado() {
        var gestor = new GestorRecursos();
        assertEquals(26, gestor.estado().size());
        for (var tipo : TipoRecurso.values()) assertEquals(tipo.capacidad(), gestor.disponibles(tipo));
        assertTrue(gestor.estado().stream().anyMatch(u -> u.id().equals("C01")));
        assertTrue(gestor.estado().stream().anyMatch(u -> u.id().equals("S01")));
    }

    @Test void adquisicionEsCompletaYLiberaInclusoTrasExcepcion() {
        var gestor = new GestorRecursos();
        var escaner = new RequisitosRecursos(Map.of(ESCANER, 1));
        var combinacion = new RequisitosRecursos(Map.of(ESCANER, 1, ESTACION_EMPAQUE, 1));
        try (var primero = gestor.intentarAsignar("primero", escaner).orElseThrow()) {
            assertTrue(gestor.intentarAsignar("segundo", combinacion).isEmpty());
            assertEquals(6, gestor.disponibles(ESTACION_EMPAQUE));
            assertTrue(gestor.esperando().containsKey("segundo"));
            assertEquals("primero", gestor.estado().stream().filter(u -> u.id().equals("S01")).findFirst().orElseThrow().propietario());
        }
        assertThrows(IllegalStateException.class, () -> {
            try (var segundo = gestor.intentarAsignar("segundo", combinacion).orElseThrow()) {
                assertEquals(0, gestor.disponibles(ESCANER));
                throw new IllegalStateException("fallo");
            }
        });
        assertEquals(1, gestor.disponibles(ESCANER));
        assertEquals(6, gestor.disponibles(ESTACION_EMPAQUE));
        assertFalse(gestor.esperando().containsKey("segundo"));
    }

    @Test void cierreEsIdempotente() {
        var gestor = new GestorRecursos();
        var asignacion = gestor.intentarAsignar("pedido", new RequisitosRecursos(Map.of(AREA_CARGA, 1))).orElseThrow();
        asignacion.close();
        asignacion.close();
        assertEquals(2, gestor.disponibles(AREA_CARGA));
    }

    @Test void competenciaConcurrentePorEscanerTieneUnSoloPropietario() throws Exception {
        var gestor = new GestorRecursos();
        var tenedor = gestor.intentarAsignar("inicial", new RequisitosRecursos(Map.of(ESCANER, 1))).orElseThrow();
        var resultados = Concurrencia.ejecutar(32, i -> gestor.intentarAsignar("op-" + i,
                new RequisitosRecursos(Map.of(ESCANER, 1))).isPresent());
        assertEquals(0, resultados.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, gestor.estado().stream().filter(u -> u.tipo() == ESCANER && u.propietario() != null).count());
        tenedor.close();
        try (var siguiente = gestor.intentarAsignar("op-0", new RequisitosRecursos(Map.of(ESCANER, 1))).orElseThrow()) {
            assertEquals(0, gestor.disponibles(ESCANER));
        }
    }

    @Test void intentosSimultaneosNoCompartenUnidadFisica() throws Exception {
        var gestor = new GestorRecursos();
        var listos = new CountDownLatch(8);
        var inicio = new CountDownLatch(1);
        var intentaron = new CountDownLatch(8);
        var liberar = new CountDownLatch(1);
        var ejecutor = Executors.newFixedThreadPool(8);
        try {
            var futuros = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                int numero = i;
                futuros.add(ejecutor.submit(() -> {
                    listos.countDown();
                    inicio.await();
                    var asignacion = gestor.intentarAsignar("pedido-" + numero,
                            new RequisitosRecursos(Map.of(ESCANER, 1)));
                    intentaron.countDown();
                    if (asignacion.isPresent()) {
                        try (var permiso = asignacion.orElseThrow()) { liberar.await(); }
                        return true;
                    }
                    return false;
                }));
            }
            assertTrue(listos.await(10, TimeUnit.SECONDS));
            inicio.countDown();
            assertTrue(intentaron.await(10, TimeUnit.SECONDS));
            assertEquals(0, gestor.disponibles(ESCANER));
            liberar.countDown();
            int exitos = 0;
            for (var futuro : futuros) if (futuro.get(10, TimeUnit.SECONDS)) exitos++;
            assertEquals(1, exitos);
            assertEquals(1, gestor.disponibles(ESCANER));
        } finally {
            inicio.countDown();
            liberar.countDown();
            ejecutor.shutdownNow();
            assertTrue(ejecutor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}

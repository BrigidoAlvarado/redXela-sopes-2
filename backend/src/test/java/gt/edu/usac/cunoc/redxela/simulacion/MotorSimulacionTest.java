package gt.edu.usac.cunoc.redxela.simulacion;

import gt.edu.usac.cunoc.redxela.Concurrencia;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MotorSimulacionTest {
    @Test
    void iniciaConValoresConfiguradosYValidaTransiciones() throws Exception {
        try (var motor = new MotorSimulacion(2, 60)) {
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
            assertEquals(0, motor.tiempoActual());
            assertThrows(IllegalStateException.class, motor::pausar);
            assertThrows(IllegalStateException.class, motor::reanudar);
            assertThrows(IllegalStateException.class, () -> motor.enviarTrabajo(contexto -> { }));
            motor.iniciar();
            assertEquals(EstadoSimulacion.EJECUTANDO, motor.estado());
            assertEquals(1, motor.corridaActual());
            assertEquals(60, motor.factorTemporal());
            assertThrows(IllegalStateException.class, motor::iniciar);
            motor.pausar();
            assertEquals(EstadoSimulacion.PAUSADA, motor.estado());
            assertThrows(IllegalStateException.class, motor::pausar);
            assertThrows(IllegalStateException.class, motor::iniciar);
            assertThrows(IllegalStateException.class, () -> motor.enviarTrabajo(contexto -> { }));
            motor.reanudar();
            assertThrows(IllegalStateException.class, motor::reanudar);
            motor.detener();
            motor.detener();
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
        }
    }

    @Test
    void noAdmiteTrabajosDeUnaCorridaAnterior() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            motor.iniciar();
            long anterior = motor.estadoCorrida().corrida();
            motor.detener();
            motor.iniciar();
            assertThrows(IllegalStateException.class, () -> motor.enviarTrabajo(anterior, contexto -> { }));
            motor.enviarTrabajo(motor.corridaActual(), contexto -> { }).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void iniciosConcurrentesSoloCreanUnaCorrida() throws Exception {
        try (var motor = new MotorSimulacion(2, 60)) {
            var resultados = Concurrencia.ejecutar(32, indice -> {
                try {
                    motor.iniciar();
                    return true;
                } catch (IllegalStateException yaActiva) {
                    return false;
                }
            });
            assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, motor.corridaActual());
        }
    }

    @Test
    void dosTareasEjecutanEnParaleloYSaturacionSeRechazaSinColaOculta() throws Exception {
        try (var motor = new MotorSimulacion(2, 60)) {
            var dentro = new CountDownLatch(2);
            var continuar = new CountDownLatch(1);
            var hilos = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
            TrabajoSimulado trabajo = contexto -> {
                contexto.avanzar(() -> {
                    hilos.add(Thread.currentThread());
                    dentro.countDown();
                });
                continuar.await(); // Coordinación de la prueba fuera de la sección de progreso.
            };
            motor.iniciar();
            var primero = motor.enviarTrabajo(trabajo);
            var segundo = motor.enviarTrabajo(trabajo);
            esperar(dentro);
            assertEquals(2, hilos.size());
            assertThrows(RejectedExecutionException.class, () -> motor.enviarTrabajo(contexto -> { }));
            continuar.countDown();
            primero.get(10, TimeUnit.SECONDS);
            segundo.get(10, TimeUnit.SECONDS);
            motor.detener();
            assertTrue(hilos.stream().noneMatch(Thread::isAlive));
        }
    }

    @Test
    void tareaSoloConfirmaSuSiguientePasoConMotorEjecutando() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            var preparada = new CountDownLatch(1);
            var solicitarPaso = new CountDownLatch(1);
            var avance = new AtomicInteger();
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> {
                preparada.countDown();
                solicitarPaso.await();
                contexto.avanzar(() -> {
                    assertEquals(EstadoSimulacion.EJECUTANDO, motor.estado());
                    avance.incrementAndGet();
                });
            });
            esperar(preparada);
            motor.pausar();
            solicitarPaso.countDown();
            assertEquals(0, avance.get());
            motor.reanudar();
            futuro.get(10, TimeUnit.SECONDS);
            assertEquals(1, avance.get());
        }
    }

    @Test
    void esperaSimuladaDescuentaPausaYConservaTrabajoRestante() throws Exception {
        var nanos = new AtomicLong();
        var leyendoDesdeTrabajo = new CountDownLatch(1);
        var reloj = new RelojSimulado(60, () -> {
            if (Thread.currentThread().getName().startsWith("redxela-corrida-")) {
                leyendoDesdeTrabajo.countDown();
            }
            return nanos.get();
        });
        try (var motor = new MotorSimulacion(1, reloj, Duration.ofSeconds(5))) {
            motor.iniciar();
            var terminado = new AtomicInteger();
            var futuro = motor.enviarTrabajo(contexto -> {
                contexto.esperar(Duration.ofSeconds(120));
                contexto.avanzar(terminado::incrementAndGet);
            });
            esperar(leyendoDesdeTrabajo);
            // Tomar el lock para pausar confirma que esperar ya soltó el lock en awaitNanos.
            motor.pausar();
            nanos.addAndGet(Duration.ofSeconds(100).toNanos());
            motor.cambiarVelocidad(30);
            assertEquals(0, motor.tiempoActual());
            assertEquals(0, terminado.get());
            motor.reanudar();
            assertEquals(0, motor.tiempoActual());
            nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            motor.cambiarVelocidad(30);
            assertEquals(Duration.ofSeconds(60).toNanos(), motor.tiempoActual());
            assertEquals(0, terminado.get());
            nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            motor.cambiarVelocidad(30);
            futuro.get(10, TimeUnit.SECONDS);
            assertEquals(1, terminado.get());
            assertEquals(Duration.ofSeconds(120).toNanos(), motor.tiempoActual());
        }
    }

    @Test
    void cambioDeVelocidadDespiertaUnaEsperaLargaSinTickPeriodico() throws Exception {
        var nanos = new AtomicLong();
        var esperando = new CountDownLatch(1);
        var reloj = new RelojSimulado(0.000001, () -> {
            if (Thread.currentThread().getName().startsWith("redxela-corrida-")) {
                esperando.countDown();
            }
            return nanos.get();
        });
        try (var motor = new MotorSimulacion(1, reloj, Duration.ofSeconds(5))) {
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> contexto.esperar(Duration.ofSeconds(120)));
            esperar(esperando);
            motor.cambiarVelocidad(120);
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            motor.cambiarVelocidad(120);
            futuro.get(10, TimeUnit.SECONDS);
            assertEquals(Duration.ofSeconds(120).toNanos(), motor.tiempoActual());
        }
    }

    @Test
    void detenerCancelaFutureEsperaFinallyYTerminaFisicamenteLosHilos() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            var dentro = new CountDownLatch(1);
            var limpio = new CountDownLatch(1);
            var trabajador = new AtomicReference<Thread>();
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> {
                trabajador.set(Thread.currentThread());
                try {
                    contexto.avanzar(dentro::countDown);
                    contexto.esperar(Duration.ofDays(1));
                } finally {
                    limpio.countDown();
                }
            });
            esperar(dentro);
            motor.detener();
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
            assertTrue(futuro.isCancelled());
            assertThrows(CancellationException.class, futuro::get);
            assertEquals(0, limpio.getCount());
            assertFalse(trabajador.get().isAlive());
        }
    }

    @Test
    void detenerMientrasPausadaDespiertaYCancelaElTrabajo() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            var preparada = new CountDownLatch(1);
            var solicitarPaso = new CountDownLatch(1);
            var avance = new AtomicInteger();
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> {
                preparada.countDown();
                solicitarPaso.await();
                contexto.avanzar(avance::incrementAndGet);
            });
            esperar(preparada);
            motor.pausar();
            solicitarPaso.countDown();
            motor.detener();
            assertTrue(futuro.isCancelled());
            assertEquals(0, avance.get());
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
        }
    }

    @Test
    void reinicioDescartaContextosViejosYReiniciaElRelojTrasTerminarLaCorrida() throws Exception {
        var nanos = new AtomicLong();
        try (var motor = new MotorSimulacion(1, new RelojSimulado(60, nanos::get), Duration.ofSeconds(5))) {
            var contextoAnterior = new AtomicReference<MotorSimulacion.ContextoTrabajo>();
            motor.iniciar();
            motor.enviarTrabajo(contextoAnterior::set).get(10, TimeUnit.SECONDS);
            nanos.addAndGet(Duration.ofSeconds(1).toNanos());
            motor.detener();
            assertEquals(Duration.ofSeconds(60).toNanos(), motor.tiempoActual());
            motor.iniciar();
            assertEquals(2, motor.corridaActual());
            assertEquals(0, motor.tiempoActual());
            assertThrows(InterruptedException.class, () -> contextoAnterior.get().avanzar(() -> fail("Contexto obsoleto")));
            // La excepción de control no debe contaminar el hilo que hace la prueba.
            assertFalse(Thread.currentThread().isInterrupted());
            var avances = new AtomicInteger();
            motor.enviarTrabajo(contexto -> contexto.avanzar(avances::incrementAndGet)).get(10, TimeUnit.SECONDS);
            assertEquals(1, avances.get());
        }
    }

    @Test
    void detencionesConcurrentesNoDejanTrabajadoresActivos() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            var dentro = new CountDownLatch(1);
            var trabajador = new AtomicReference<Thread>();
            motor.iniciar();
            motor.enviarTrabajo(contexto -> {
                trabajador.set(Thread.currentThread());
                contexto.avanzar(dentro::countDown);
                contexto.esperar(Duration.ofDays(1));
            });
            esperar(dentro);
            Concurrencia.ejecutar(16, indice -> {
                try {
                    motor.detener();
                    return true;
                } catch (InterruptedException interrumpido) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrumpido);
                }
            });
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
            assertFalse(trabajador.get().isAlive());
        }
    }

    @Test
    void tareaQueIgnoraInterrupcionImpideDeclararDetenidaOIniciarOtraCorrida() throws Exception {
        var nanos = new AtomicLong();
        var motor = new MotorSimulacion(1, new RelojSimulado(60, nanos::get), Duration.ZERO);
        var dentro = new CountDownLatch(1);
        var permitirSalida = new CountDownLatch(1);
        var termino = new CountDownLatch(1);
        var hilo = new AtomicReference<Thread>();
        try {
            motor.iniciar();
            motor.enviarTrabajo(contexto -> {
                hilo.set(Thread.currentThread());
                dentro.countDown();
                try {
                    // Contraejemplo deliberado: código ajeno al contrato cooperativo.
                    boolean salir = false;
                    while (!salir) {
                        try {
                            permitirSalida.await();
                            salir = true;
                        } catch (InterruptedException ignoradaSoloEnEstaPrueba) {
                            // No hay espera activa: vuelve a bloquearse en el latch.
                        }
                    }
                } finally {
                    termino.countDown();
                }
            });
            esperar(dentro);
            assertThrows(IllegalStateException.class, motor::detener);
            assertEquals(EstadoSimulacion.DETENIENDO, motor.estado());
            assertThrows(IllegalStateException.class, motor::iniciar);
            assertThrows(IllegalStateException.class, () -> motor.enviarTrabajo(contexto -> { }));
        } finally {
            permitirSalida.countDown();
            esperar(termino);
            hilo.get().join(10_000);
            assertFalse(hilo.get().isAlive());
            motor.detener();
            assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
        }
    }

    @Test
    void interrupcionDelControladorConservaLaSenalYNoMienteSobreElEstado() throws Exception {
        var motor = new MotorSimulacion(1, 60);
        try {
            motor.iniciar();
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, motor::detener);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(EstadoSimulacion.DETENIENDO, motor.estado());
            assertThrows(IllegalStateException.class, motor::iniciar);
        } finally {
            Thread.interrupted(); // Limpia únicamente la interrupción provocada por esta prueba.
            motor.close();
        }
        assertEquals(EstadoSimulacion.DETENIDA, motor.estado());
    }

    @Test
    void erroresDeTareaSeObservanEnFutureYNoSeOcultanComoExito() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> { throw new IllegalArgumentException("Fallo de prueba"); });
            var fallo = assertThrows(ExecutionException.class, () -> futuro.get(10, TimeUnit.SECONDS));
            assertEquals("Fallo de prueba", fallo.getCause().getMessage());
            assertEquals(EstadoSimulacion.EJECUTANDO, motor.estado());
        }
    }

    @Test
    void trabajadorNoPuedeEsperarSuPropiaTerminacion() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> motor.detener());
            var fallo = assertThrows(ExecutionException.class, () -> futuro.get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, fallo.getCause());
            assertEquals(EstadoSimulacion.EJECUTANDO, motor.estado());
        }
    }

    @Test
    void cancelacionIndividualInterrumpeEsperasYFinalizaElTrabajo() throws Exception {
        try (var motor = new MotorSimulacion(1, 60)) {
            var dentro = new CountDownLatch(1);
            var limpio = new CountDownLatch(1);
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> {
                try {
                    contexto.avanzar(dentro::countDown);
                    contexto.esperar(Duration.ofDays(1));
                } finally {
                    limpio.countDown();
                }
            });
            esperar(dentro);
            assertTrue(futuro.cancel(true));
            esperar(limpio);
            assertTrue(futuro.isCancelled());
            assertEquals(EstadoSimulacion.EJECUTANDO, motor.estado());
        }
    }

    @Test
    void parametrosYDuracionesInvalidasSeRechazan() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new MotorSimulacion(0, 60));
        assertThrows(IllegalArgumentException.class, () -> new MotorSimulacion(1, Double.NaN));
        try (var motor = new MotorSimulacion(1, 60)) {
            motor.iniciar();
            var futuro = motor.enviarTrabajo(contexto -> {
                assertThrows(IllegalArgumentException.class, () -> contexto.esperar(Duration.ofNanos(-1)));
                contexto.esperar(Duration.ZERO);
            });
            futuro.get(10, TimeUnit.SECONDS);
        }
    }

    private static void esperar(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "No se alcanzó el punto de coordinación");
    }
}

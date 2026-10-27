package gt.edu.usac.cunoc.redxela.conflicto;

import gt.edu.usac.cunoc.redxela.catalogo.*;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.pedidos.*;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.MotorControlado;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class GeneradorPedidosTest {
    @Test void generaConRelojSimuladoRespetaPausaYUsaElFlujoExistente() throws Exception {
        var catalogo = new CatalogoProductos(); var clientes = new ClienteService();
        var pedidos = new PedidoService(catalogo, clientes); var recursos = new GestorRecursos();
        var inventario = new InventarioService(catalogo); var control = new MotorControlado();
        var motor = control.motor(); var aviso = new Semaphore(0);
        try (motor; var procesador = new ProcesadorPedidos(pedidos, motor, recursos, inventario);
             var generador = new GeneradorPedidos(clientes, pedidos, procesador, catalogo, motor)) {
            procesador.registrarAvisos(aviso); motor.iniciar(); generador.iniciar();
            esperarHilo();
            control.avanzarReal(Duration.ofMillis(490));
            assertTrue(pedidos.listar().isEmpty());
            control.avanzarReal(Duration.ofMillis(10));
            esperar(aviso, () -> pedidos.listar().size() == 1);
            motor.pausar();
            control.avanzarReal(Duration.ofSeconds(10));
            assertEquals(1, pedidos.listar().size());
            motor.reanudar();
            for (int i = 2; i <= 10; i++) {
                esperarHilo();
                control.avanzarReal(Duration.ofMillis(500));
                int esperados = i;
                esperar(aviso, () -> pedidos.listar().size() == esperados);
            }
            generador.detener();
            assertFalse(generador.activo());
            assertEquals(10, clientes.listar().size());
            assertEquals(5, pedidos.listar().stream().map(Pedido::nivelServicio).distinct().count());
            assertEquals(5, pedidos.listar().stream().flatMap(p -> p.lineas().stream()).map(l -> l.producto().tipo()).distinct().count());
            for (var pedido : pedidos.listar()) {
                assertEquals(EstadoPedido.ESPERANDO_INVENTARIO, procesador.consultar(pedido.id()).estado());
                assertTrue(pedido.lineas().stream().allMatch(l -> catalogo.buscar(l.producto().id()).equals(l.producto())));
            }
            assertEquals(0, Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive()
                    && t.getName().equals("redxela-generador-pedidos")).count());
        }
    }

    private static void esperarHilo() {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < limite) {
            boolean espera = Thread.getAllStackTraces().entrySet().stream().anyMatch(e -> e.getKey().getName().equals("redxela-generador-pedidos")
                    && (e.getKey().getState() == Thread.State.TIMED_WAITING || e.getKey().getState() == Thread.State.WAITING)
                    && java.util.Arrays.stream(e.getValue()).anyMatch(f -> f.getMethodName().equals("esperarTiempo")));
            if (espera) return;
            Thread.yield();
        }
        fail("El generador no llegó a esperar el reloj");
    }

    private static void esperar(Semaphore aviso, BooleanSupplier condicion) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condicion.getAsBoolean()) {
            long restante = limite - System.nanoTime();
            assertTrue(restante > 0 && aviso.tryAcquire(restante, TimeUnit.NANOSECONDS));
        }
    }
}

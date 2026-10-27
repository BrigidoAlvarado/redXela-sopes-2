package gt.edu.usac.cunoc.redxela.pedidos;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/** Un solo hilo genera llegadas; los pedidos siguen el mismo camino que los manuales. */
public final class GeneradorPedidos implements AutoCloseable {
    private final ClienteService clientes;
    private final PedidoService pedidos;
    private final ProcesadorPedidos procesador;
    private final CatalogoProductos catalogo;
    private final MotorSimulacion motor;
    private final Random aleatorio = new Random();
    private volatile Duration intervalo = Duration.ofSeconds(30);
    private Thread hilo;

    public GeneradorPedidos(ClienteService clientes, PedidoService pedidos, ProcesadorPedidos procesador,
                             CatalogoProductos catalogo, MotorSimulacion motor) {
        this.clientes = Objects.requireNonNull(clientes); this.pedidos = Objects.requireNonNull(pedidos);
        this.procesador = Objects.requireNonNull(procesador); this.catalogo = Objects.requireNonNull(catalogo);
        this.motor = Objects.requireNonNull(motor);
    }

    public synchronized boolean activo() { return hilo != null && hilo.isAlive(); }
    public Duration intervalo() { return intervalo; }
    public void cambiarIntervalo(Duration nuevo) {
        Objects.requireNonNull(nuevo);
        if (nuevo.isZero() || nuevo.isNegative()) throw new IllegalArgumentException("Intervalo debe ser positivo");
        nuevo.toNanos();
        intervalo = nuevo;
    }

    public synchronized void iniciar() {
        if (activo()) throw new IllegalStateException("Generador ya activo");
        var estado = motor.estadoCorrida();
        if (estado.estado() != EstadoSimulacion.EJECUTANDO) throw new IllegalStateException("Motor no está ejecutando");
        hilo = new Thread(() -> generar(estado.corrida()), "redxela-generador-pedidos");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void generar(long corrida) {
        try {
            int secuencia = 0;
            while (!Thread.currentThread().isInterrupted()) {
                motor.esperarTiempo(corrida, intervalo);
                int numero = secuencia++;
                // La puerta del motor impide crear pedidos después de pausar o detener.
                if (!motor.avanzarSiEjecutando(corrida, () -> {
                    var niveles = NivelServicio.values();
                    var nivel = niveles[numero % niveles.length];
                    var cliente = clientes.registrar("Cliente automático", UUID.randomUUID() + "@redxela.local", nivel);
                    var productos = new ArrayList<>(catalogo.listar());
                    var principal = productos.remove(numero % productos.size());
                    Collections.shuffle(productos, aleatorio);
                    productos.add(0, principal);
                    int cantidadLineas = 1 + aleatorio.nextInt(3);
                    var lineas = new ArrayList<SolicitudLineaPedido>();
                    for (int i = 0; i < cantidadLineas; i++)
                        lineas.add(new SolicitudLineaPedido(productos.get(i).id(), 1 + aleatorio.nextInt(20)));
                    var pedido = pedidos.crear(cliente.id(), nivel, lineas);
                    procesador.procesar(pedido.id());
                })) break;
            }
        } catch (InterruptedException interrumpido) { Thread.currentThread().interrupt(); }
    }

    public void detener() throws InterruptedException {
        Thread anterior;
        synchronized (this) { anterior = hilo; if (anterior != null) anterior.interrupt(); }
        if (anterior != null) anterior.join();
        synchronized (this) { if (hilo == anterior) hilo = null; }
    }

    @Override public void close() {
        try { detener(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Cierre interrumpido", ex); }
    }
}

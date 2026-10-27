package gt.edu.usac.cunoc.redxela.api;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.conflicto.DemostracionDeadlock;
import gt.edu.usac.cunoc.redxela.inventario.InventarioService;
import gt.edu.usac.cunoc.redxela.metricas.MetricasService;
import gt.edu.usac.cunoc.redxela.pedidos.*;
import gt.edu.usac.cunoc.redxela.recepcion.RecepcionService;
import gt.edu.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.edu.usac.cunoc.redxela.simulacion.MotorSimulacion;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;
import static gt.edu.usac.cunoc.redxela.api.ContratosApi.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final MotorSimulacion motor;
    private final ClienteService clientes;
    private final PedidoService pedidos;
    private final ProcesadorPedidos procesador;
    private final GeneradorPedidos generador;
    private final GestorRecursos recursos;
    private final InventarioService inventario;
    private final RecepcionService recepcion;
    private final MetricasService metricas;
    private final DemostracionDeadlock deadlock;
    private final CatalogoProductos catalogo;

    public ApiController(MotorSimulacion motor, ClienteService clientes, PedidoService pedidos, ProcesadorPedidos procesador,
                         GeneradorPedidos generador, GestorRecursos recursos, InventarioService inventario,
                         RecepcionService recepcion, MetricasService metricas, DemostracionDeadlock deadlock, CatalogoProductos catalogo) {
        this.motor = motor; this.clientes = clientes; this.pedidos = pedidos; this.procesador = procesador;
        this.generador = generador; this.recursos = recursos; this.inventario = inventario; this.recepcion = recepcion;
        this.metricas = metricas; this.deadlock = deadlock; this.catalogo = catalogo;
    }
    public record EstadoSimulacionVista(gt.edu.usac.cunoc.redxela.simulacion.EstadoSimulacion estado, long corrida,
                                       long tiempoSimuladoMillis, double factorTemporal) { }
    public record Recurso(String id, gt.edu.usac.cunoc.redxela.recursos.TipoRecurso tipo, boolean disponible, String propietario) { }
    public record GeneradorVista(boolean activo, long intervaloMillis) { }
    public record PlanificacionVista(gt.edu.usac.cunoc.redxela.planificacion.PoliticaPlanificacion politica, long intervaloAgingMillis) { }
    @GetMapping("/simulacion") public EstadoSimulacionVista simulacion() {
        return motor.conEstado(e -> new EstadoSimulacionVista(e.estado(), e.corrida(), e.tiempoSimulado() / 1_000_000, motor.factorTemporal()));
    }
    @PostMapping("/simulacion/iniciar") public EstadoSimulacionVista iniciar() { motor.iniciar(); return simulacion(); }
    @PostMapping("/simulacion/pausar") public EstadoSimulacionVista pausar() { motor.pausar(); return simulacion(); }
    @PostMapping("/simulacion/reanudar") public EstadoSimulacionVista reanudar() { motor.reanudar(); return simulacion(); }
    @PostMapping("/simulacion/detener") public EstadoSimulacionVista detener() throws InterruptedException { motor.detener(); return simulacion(); }
    @PutMapping("/simulacion/velocidad") public EstadoSimulacionVista velocidad(@RequestBody VelocidadSolicitud s) { motor.cambiarVelocidad(s.factor()); return simulacion(); }

    @GetMapping("/clientes") public List<gt.edu.usac.cunoc.redxela.clientes.Cliente> clientes() { return clientes.listar(); }
    @PostMapping("/clientes") @ResponseStatus(HttpStatus.CREATED)
    public gt.edu.usac.cunoc.redxela.clientes.Cliente crearCliente(@RequestBody ClienteSolicitud s) { return clientes.registrar(s.nombre(), s.correo(), s.servicioPreferido()); }
    @GetMapping("/clientes/{id}") public gt.edu.usac.cunoc.redxela.clientes.Cliente cliente(@PathVariable UUID id) {
        try { return clientes.buscar(id); } catch (IllegalArgumentException ex) { throw new NoSuchElementException(ex.getMessage()); }
    }
    @GetMapping("/catalogo") public List<gt.edu.usac.cunoc.redxela.catalogo.Producto> catalogo() { return catalogo.listar(); }
    @GetMapping("/pedidos") public List<PedidoVista> pedidos() { return pedidos.listar().stream().map(this::vista).toList(); }
    @GetMapping("/pedidos/{id}") public PedidoVista pedido(@PathVariable UUID id) {
        try { return vista(pedidos.buscar(id)); } catch (IllegalArgumentException ex) { throw new NoSuchElementException(ex.getMessage()); }
    }
    private PedidoVista vista(Pedido p) {
        try { return new PedidoVista(p, progreso(procesador.consultar(p.id()))); }
        catch (IllegalArgumentException noAdmitido) { return new PedidoVista(p, null); }
    }
    @GetMapping("/pedidos/cola") public List<Cola> cola() { return procesador.consultarCola().stream().map(ContratosApi::cola).toList(); }
    @PostMapping("/pedidos") @ResponseStatus(HttpStatus.CREATED)
    public PedidoVista crearPedido(@RequestBody PedidoSolicitud s) throws InterruptedException {
        // La puerta del motor impide detener o pausar entre la creación y la admisión.
        Pedido[] creado = new Pedido[1];
        if (!motor.avanzarSiEjecutando(() -> {
            if (s.clienteId() == null) throw new IllegalArgumentException("Cliente es obligatorio");
            cliente(s.clienteId());
            creado[0] = pedidos.crear(s.clienteId(), s.nivelServicio(), s.lineas());
            try { procesador.procesar(creado[0].id()); }
            catch (RuntimeException ex) { throw new IllegalStateException("Pedido creado pero no admitido; ID: " + creado[0].id(), ex); }
        })) throw new IllegalStateException("La simulación debe estar ejecutándose");
        return vista(creado[0]);
    }
    @GetMapping("/generador") public GeneradorVista generador() { return new GeneradorVista(generador.activo(), generador.intervalo().toMillis()); }
    @PostMapping("/generador/iniciar") public GeneradorVista iniciarGenerador() { generador.iniciar(); return generador(); }
    @PostMapping("/generador/detener") public GeneradorVista detenerGenerador() throws InterruptedException { generador.detener(); return generador(); }
    @PutMapping("/generador/intervalo") public GeneradorVista intervalo(@RequestBody IntervaloSolicitud s) {
        generador.cambiarIntervalo(Duration.ofMillis(s.intervaloMillis())); return generador();
    }
    @GetMapping("/recursos") public List<Recurso> recursos() {
        return recursos.estado().stream().map(u -> new Recurso(u.id(), u.tipo(), u.disponible(), u.propietario())).toList();
    }
    @GetMapping("/inventario") public List<InventarioService.Existencia> inventario() { return inventario.existencias(); }
    @GetMapping("/inventario/ubicaciones") public List<InventarioService.EstadoUbicacion> ubicaciones() { return inventario.ubicaciones(); }
    @PostMapping("/recepciones") @ResponseStatus(HttpStatus.ACCEPTED)
    public RecepcionService.Vista recibir(@RequestBody RecepcionSolicitud s) { return recepcion.consultar(recepcion.registrar(s.productoId(), s.cantidad())); }
    @GetMapping("/recepciones/{id}") public RecepcionService.Vista recepcion(@PathVariable UUID id) {
        try { return recepcion.consultar(id); } catch (IllegalArgumentException ex) { throw new NoSuchElementException(ex.getMessage()); }
    }
    @GetMapping("/planificacion") public PlanificacionVista planificacion() { return new PlanificacionVista(procesador.politica(), procesador.intervaloAging().toMillis()); }
    @PutMapping("/planificacion") public PlanificacionVista planificacion(@RequestBody PlanificacionSolicitud s) {
        if (s.politica() == null) throw new IllegalArgumentException("Política es obligatoria");
        if (s.intervaloAgingMillis() != null) procesador.cambiarIntervaloAging(Duration.ofMillis(s.intervaloAgingMillis()));
        procesador.cambiarPolitica(s.politica()); return planificacion();
    }
    @GetMapping("/metricas") public Metricas metricas() { return ContratosApi.metricas(metricas.resumenActual()); }
    @GetMapping("/deadlock") public DemostracionDeadlock.Vista deadlock() { return deadlock.consultar(); }
    @PostMapping("/deadlock/iniciar") public DemostracionDeadlock.Vista iniciarDeadlock(@RequestBody ConflictoSolicitud s) {
        if (s.pedidoA() == null || s.pedidoB() == null) throw new IllegalArgumentException("Dos pedidos son obligatorios");
        deadlock.iniciar(s.pedidoA(), s.pedidoB()); return deadlock.consultar();
    }
    @PostMapping("/deadlock/resolver") public DemostracionDeadlock.Vista resolverDeadlock(@RequestBody GanadorSolicitud s) {
        if (s.pedidoGanador() == null) throw new IllegalArgumentException("Ganador es obligatorio");
        deadlock.resolver(s.pedidoGanador()); return deadlock.consultar();
    }
}

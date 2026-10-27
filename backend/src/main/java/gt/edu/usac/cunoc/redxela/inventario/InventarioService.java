package gt.edu.usac.cunoc.redxela.inventario;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.catalogo.Producto;
import gt.edu.usac.cunoc.redxela.catalogo.TipoMercancia;
import gt.edu.usac.cunoc.redxela.pedidos.Pedido;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Semaphore;

/** El mismo monitor protege existencias, reservas y distribución física. */
public final class InventarioService {
    private final CatalogoProductos catalogo;
    private final List<Ubicacion> ubicaciones = new ArrayList<>();
    private final Map<UUID, Map<String, Integer>> reservas = new HashMap<>();
    private final Set<UUID> lotes = new HashSet<>();
    private final CopyOnWriteArraySet<Semaphore> avisos = new CopyOnWriteArraySet<>();

    public InventarioService(CatalogoProductos catalogo) {
        this.catalogo = Objects.requireNonNull(catalogo);
        for (int p = 1; p <= 5; p++) for (int e = 1; e <= 5; e++) for (int n = 1; n <= 4; n++)
            ubicaciones.add(new Ubicacion("P%02d-E%02d-N%02d".formatted(p, e, n)));
    }

    public void registrarAvisos(Semaphore aviso) { avisos.add(Objects.requireNonNull(aviso)); }
    public void retirarAvisos(Semaphore aviso) { avisos.remove(aviso); }
    private void avisar() { avisos.forEach(Semaphore::release); }

    public record Existencia(Producto producto, int fisico, int reservado, int disponible) { }
    public record Fragmento(UUID lote, String producto, int cantidad) { }
    public record EstadoUbicacion(String id, int capacidad, int ocupado, List<Fragmento> fragmentos) { }
    private static final class Ubicacion {
        final String id;
        final List<Fragmento> fragmentos = new ArrayList<>();
        int ocupado;
        Ubicacion(String id) { this.id = id; }
    }

    public synchronized List<EstadoUbicacion> ubicaciones() {
        return ubicaciones.stream().map(u -> new EstadoUbicacion(u.id, 10, u.ocupado, List.copyOf(u.fragmentos))).toList();
    }
    public synchronized List<Existencia> existencias() {
        return catalogo.listar().stream().map(p -> existencia(p.id())).toList();
    }
    public synchronized Existencia existencia(String codigo) {
        Producto p = catalogo.buscar(codigo);
        int fisico = ubicaciones.stream().flatMap(u -> u.fragmentos.stream()).filter(f -> f.producto().equals(p.id()))
                .mapToInt(Fragmento::cantidad).sum();
        int reservado = reservas.values().stream().mapToInt(r -> r.getOrDefault(p.id(), 0)).sum();
        return new Existencia(p, fisico, reservado, fisico - reservado);
    }

    /** First Fit completo: antes de escribir se comprueba que todas las unidades caben. */
    public synchronized boolean almacenar(UUID lote, String codigo, int cantidad) {
        Objects.requireNonNull(lote);
        Producto p = catalogo.buscar(codigo);
        if (cantidad < 1 || cantidad > (10 / p.volumenPorUnidad()) * 100)
            throw new IllegalArgumentException("Cantidad imposible para el almacén");
        if (lotes.contains(lote)) throw new IllegalStateException("Lote ya almacenado");
        int capacidad = ubicaciones.stream().mapToInt(u -> (10 - u.ocupado) / p.volumenPorUnidad()).sum();
        if (cantidad > capacidad) return false;
        int restante = cantidad;
        for (Ubicacion u : ubicaciones) {
            int unidades = Math.min(restante, (10 - u.ocupado) / p.volumenPorUnidad());
            if (unidades > 0) {
                u.fragmentos.add(new Fragmento(lote, p.id(), unidades));
                u.ocupado += unidades * p.volumenPorUnidad();
                restante -= unidades;
            }
            if (restante == 0) break;
        }
        lotes.add(lote);
        avisar();
        return true;
    }

    /** Comprobación y reserva de todas las líneas son una única operación atómica. */
    public synchronized boolean reservar(Pedido pedido) {
        Objects.requireNonNull(pedido);
        if (reservas.containsKey(pedido.id())) throw new IllegalStateException("Pedido ya reservado");
        if (pedido.lineas().stream().anyMatch(l -> existencia(l.producto().id()).disponible() < l.cantidad())) return false;
        Map<String, Integer> lineas = new LinkedHashMap<>();
        pedido.lineas().forEach(l -> lineas.put(l.producto().id(), l.cantidad()));
        reservas.put(pedido.id(), lineas);
        return true;
    }

    /** Cada etapa RETIRO confirma únicamente sus productos; no se vuelve a descontar. */
    public synchronized void retirar(UUID pedido, TipoMercancia tipo) {
        Map<String, Integer> reserva = reservas.get(pedido);
        if (reserva == null) throw new IllegalStateException("No existe reserva para el pedido");
        var productos = reserva.keySet().stream().filter(id -> catalogo.buscar(id).tipo() == tipo).toList();
        if (productos.isEmpty()) throw new IllegalStateException("Retiro ya confirmado o tipo no reservado");
        for (String id : productos) {
            int restante = reserva.get(id);
            int volumen = catalogo.buscar(id).volumenPorUnidad();
            for (Ubicacion u : ubicaciones) {
                for (var it = u.fragmentos.listIterator(); it.hasNext() && restante > 0;) {
                    Fragmento f = it.next();
                    if (!f.producto().equals(id)) continue;
                    int tomado = Math.min(restante, f.cantidad());
                    if (tomado == f.cantidad()) it.remove();
                    else it.set(new Fragmento(f.lote(), f.producto(), f.cantidad() - tomado));
                    u.ocupado -= tomado * volumen;
                    restante -= tomado;
                }
            }
            if (restante != 0) throw new IllegalStateException("Inconsistencia de inventario");
            reserva.remove(id);
        }
        if (reserva.isEmpty()) reservas.remove(pedido);
        avisar();
    }

    public synchronized void cancelarReserva(UUID pedido) {
        if (reservas.remove(pedido) != null) avisar();
    }
}

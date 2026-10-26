package gt.edu.usac.cunoc.redxela.clientes;

import gt.edu.usac.cunoc.redxela.catalogo.NivelServicio;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.requerido;

/** Registro en memoria. Ambos índices cambian en la misma sección crítica. */
public final class ClienteService {
    private final Map<UUID, Cliente> porId = new LinkedHashMap<>();
    private final Map<String, UUID> porCorreo = new HashMap<>();

    public synchronized Cliente registrar(String nombre, String correo, NivelServicio servicio) {
        Cliente cliente = new Cliente(UUID.randomUUID(), nombre, correo, servicio);
        if (porCorreo.containsKey(cliente.correo())) {
            throw new IllegalArgumentException("Ya existe un cliente con ese correo");
        }
        porId.put(cliente.id(), cliente);
        porCorreo.put(cliente.correo(), cliente.id());
        return cliente;
    }

    public synchronized Cliente buscar(UUID id) {
        Cliente cliente = porId.get(requerido(id, "ID del cliente"));
        if (cliente == null) {
            throw new IllegalArgumentException("Cliente no registrado: " + id);
        }
        return cliente;
    }

    public synchronized List<Cliente> listar() { return List.copyOf(porId.values()); }
}

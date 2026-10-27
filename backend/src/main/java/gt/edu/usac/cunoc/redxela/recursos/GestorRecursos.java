package gt.edu.usac.cunoc.redxela.recursos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArraySet;

/** Administra unidades físicas con semáforos binarios y asignaciones atómicas. */
public final class GestorRecursos {
    private final Map<TipoRecurso, List<Unidad>> unidades = new EnumMap<>(TipoRecurso.class);
    private final Map<String, RequisitosRecursos> esperando = new LinkedHashMap<>();
    private final CopyOnWriteArraySet<Semaphore> avisos = new CopyOnWriteArraySet<>();
    private long version;

    public void registrarAvisos(Semaphore aviso) { avisos.add(Objects.requireNonNull(aviso)); }
    public void retirarAvisos(Semaphore aviso) { avisos.remove(aviso); }
    public synchronized long version() { return version; }

    public GestorRecursos() {
        for (TipoRecurso tipo : TipoRecurso.values()) {
            var lista = new ArrayList<Unidad>();
            for (int i = 1; i <= tipo.capacidad(); i++) {
                lista.add(new Unidad(tipo.identificador(i), tipo));
            }
            unidades.put(tipo, List.copyOf(lista));
        }
    }

    /** Nunca retiene una parte de la combinación cuando no se consigue completa. */
    public synchronized Optional<Asignacion> intentarAsignar(String operacion, RequisitosRecursos requisitos) {
        Objects.requireNonNull(operacion, "Operación");
        Objects.requireNonNull(requisitos, "Requisitos");
        if (operacion.isBlank()) throw new IllegalArgumentException("Operación vacía");
        if (unidades.values().stream().flatMap(List::stream).anyMatch(u -> operacion.equals(u.propietario))) {
            throw new IllegalStateException("La operación ya tiene recursos asignados");
        }
        var tomadas = new ArrayList<Unidad>();
        // El monitor hace indivisibles permisos y propietarios ante otras solicitudes.
        for (TipoRecurso tipo : TipoRecurso.values()) {
            int faltantes = requisitos.cantidad(tipo);
            for (Unidad unidad : unidades.get(tipo)) {
                if (faltantes == 0) break;
                if (unidad.permiso.tryAcquire()) {
                    tomadas.add(unidad);
                    faltantes--;
                }
            }
            if (faltantes != 0) {
                tomadas.forEach(u -> u.permiso.release());
                esperando.put(operacion, requisitos);
                avisos.forEach(Semaphore::release);
                return Optional.empty();
            }
        }
        tomadas.forEach(u -> u.propietario = operacion);
        esperando.remove(operacion);
        avisos.forEach(Semaphore::release);
        return Optional.of(new Asignacion(operacion, tomadas));
    }

    public synchronized void cancelarEspera(String operacion) { esperando.remove(operacion); }

    public synchronized Map<String, RequisitosRecursos> esperando() { return Map.copyOf(esperando); }

    public synchronized List<EstadoUnidad> estado() {
        return unidades.values().stream().flatMap(List::stream)
                .map(u -> new EstadoUnidad(u.id, u.tipo, u.propietario)).toList();
    }

    public synchronized int disponibles(TipoRecurso tipo) {
        return (int) unidades.get(Objects.requireNonNull(tipo)).stream()
                .filter(u -> u.permiso.availablePermits() == 1).count();
    }

    public record EstadoUnidad(String id, TipoRecurso tipo, String propietario) { }

    private static final class Unidad {
        final String id;
        final TipoRecurso tipo;
        final Semaphore permiso = new Semaphore(1);
        String propietario;
        Unidad(String id, TipoRecurso tipo) { this.id = id; this.tipo = tipo; }
    }

    public final class Asignacion implements AutoCloseable {
        private final String operacion;
        private final List<Unidad> tomadas;
        private boolean cerrada;
        private Asignacion(String operacion, List<Unidad> tomadas) {
            this.operacion = operacion;
            this.tomadas = List.copyOf(tomadas);
        }
        public String operacion() { return operacion; }
        public List<String> recursos() { return tomadas.stream().map(u -> u.id).toList(); }
        @Override public void close() {
            synchronized (GestorRecursos.this) {
                if (cerrada) return;
                cerrada = true;
                for (Unidad unidad : tomadas) {
                    unidad.propietario = null;
                    unidad.permiso.release();
                }
                version++;
                avisos.forEach(Semaphore::release);
            }
        }
    }
}

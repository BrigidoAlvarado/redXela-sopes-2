package gt.edu.usac.cunoc.redxela.recursos;

import java.util.EnumMap;
import java.util.Map;

import static gt.edu.usac.cunoc.redxela.dominio.Validaciones.requerido;

/** Necesidades inmutables de una etapa, nunca una asignación de recursos. */
public record RequisitosRecursos(Map<TipoRecurso, Integer> cantidades) {
    public RequisitosRecursos {
        requerido(cantidades, "Recursos");
        if (cantidades.isEmpty()) {
            throw new IllegalArgumentException("Una etapa requiere al menos un recurso");
        }
        var copia = new EnumMap<TipoRecurso, Integer>(TipoRecurso.class);
        cantidades.forEach((tipo, cantidad) -> {
            requerido(tipo, "Tipo de recurso");
            requerido(cantidad, "Cantidad de recurso");
            if (cantidad < 1 || cantidad > tipo.capacidad()) {
                throw new IllegalArgumentException("Cantidad imposible para " + tipo);
            }
            copia.put(tipo, cantidad);
        });
        cantidades = Map.copyOf(copia);
    }

    public int cantidad(TipoRecurso tipo) {
        return cantidades.getOrDefault(requerido(tipo, "Tipo de recurso"), 0);
    }
}

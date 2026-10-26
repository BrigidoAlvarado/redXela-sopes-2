package gt.edu.usac.cunoc.redxela.recursos;

/** Capacidades obligatorias del enunciado; todavía no administra permisos. */
public enum TipoRecurso {
    ESTACION_EMPAQUE("E", 6),
    AREA_CARGA("C", 2),
    ENCARGADO_BODEGA("B", 10),
    MONTACARGAS("M", 3),
    ESTACION_CALIDAD("Q", 4),
    ESCANER("S", 1);

    private final String prefijo;
    private final int capacidad;

    TipoRecurso(String prefijo, int capacidad) {
        this.prefijo = prefijo;
        this.capacidad = capacidad;
    }

    public int capacidad() { return capacidad; }

    public String identificador(int numero) {
        if (numero < 1 || numero > capacidad) {
            throw new IllegalArgumentException("Número de recurso fuera de capacidad: " + this);
        }
        return prefijo + (numero < 10 ? "0" : "") + numero;
    }
}

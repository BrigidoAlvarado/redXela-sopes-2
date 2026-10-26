package gt.edu.usac.cunoc.redxela.catalogo;

import java.time.Duration;
import java.util.Arrays;

/** Los objetivos usan tiempo simulado; Duration no obtiene ni adelanta el reloj. */
public enum NivelServicio {
    EXPRES(1, "Exprés", Duration.ofSeconds(30)),
    PRIORITARIO(2, "Prioritario", Duration.ofMinutes(10)),
    ESTANDAR(3, "Estándar", Duration.ofMinutes(30)),
    PROGRAMADO(4, "Programado", Duration.ofMinutes(60)),
    ECONOMICO(5, "Económico", Duration.ofMinutes(120));

    private final int nivel;
    private final String nombre;
    private final Duration objetivoEspera;

    NivelServicio(int nivel, String nombre, Duration objetivoEspera) {
        this.nivel = nivel;
        this.nombre = nombre;
        this.objetivoEspera = objetivoEspera;
    }

    public int nivel() { return nivel; }
    public String nombre() { return nombre; }
    public Duration objetivoEspera() { return objetivoEspera; }

    public static NivelServicio desdeNivel(int nivel) {
        return Arrays.stream(values()).filter(servicio -> servicio.nivel == nivel).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nivel de servicio inválido: " + nivel));
    }
}

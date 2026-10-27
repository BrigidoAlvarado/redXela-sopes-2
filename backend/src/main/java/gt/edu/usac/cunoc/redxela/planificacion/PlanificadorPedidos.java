package gt.edu.usac.cunoc.redxela.planificacion;

import gt.edu.usac.cunoc.redxela.pedidos.Pedido;

import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;

/** Compara instantáneas: no mantiene otra cola ni modifica pedidos. */
public final class PlanificadorPedidos {
    private PlanificadorPedidos() { }

    public record Candidato(Pedido pedido, long esperaNanos) {
        public Candidato {
            Objects.requireNonNull(pedido, "Pedido");
            if (esperaNanos < 0) throw new IllegalArgumentException("La espera no puede ser negativa");
        }
    }

    public static long intervaloNanos(Duration intervalo) {
        Objects.requireNonNull(intervalo, "Intervalo de aging");
        if (intervalo.isZero() || intervalo.isNegative()) {
            throw new IllegalArgumentException("El intervalo de aging debe ser positivo");
        }
        try {
            return intervalo.toNanos();
        } catch (ArithmeticException demasiadoGrande) {
            throw new IllegalArgumentException("Intervalo de aging demasiado grande", demasiadoGrande);
        }
    }

    public static long prioridadEfectiva(Candidato candidato, Duration intervalo) {
        return candidato.pedido().nivelServicio().nivel() - candidato.esperaNanos() / intervaloNanos(intervalo);
    }

    public static Comparator<Candidato> comparador(PoliticaPlanificacion politica, Duration intervalo) {
        Objects.requireNonNull(politica, "Política");
        long nanos = intervaloNanos(intervalo);
        Comparator<Candidato> criterio = switch (politica) {
            case PRIORIDAD -> Comparator.comparingInt(c -> c.pedido().nivelServicio().nivel());
            case AGING -> Comparator.comparingLong(c -> c.pedido().nivelServicio().nivel() - c.esperaNanos() / nanos);
            case SJF -> Comparator.comparingLong(c -> c.pedido().unidadesTotales());
        };
        return criterio.thenComparingLong(c -> c.pedido().secuenciaEntrada());
    }
}

package gt.edu.usac.cunoc.redxela.pedidos;

/** Vocabulario del dominio. F1 solo crea pedidos en CREADO, sin simular transiciones. */
public enum EstadoPedido {
    CREADO, ESPERANDO_INVENTARIO, EN_COLA, EN_PROCESO, ESPERANDO_RECURSOS,
    BLOQUEADO_DEADLOCK, RECUPERANDO, COMPLETADO, ERROR, CANCELADO
}

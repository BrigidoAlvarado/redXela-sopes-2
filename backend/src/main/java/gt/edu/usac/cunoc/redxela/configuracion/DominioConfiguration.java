package gt.edu.usac.cunoc.redxela.configuracion;

import gt.edu.usac.cunoc.redxela.catalogo.CatalogoProductos;
import gt.edu.usac.cunoc.redxela.clientes.ClienteService;
import gt.edu.usac.cunoc.redxela.pedidos.PedidoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conecta el dominio Java con Spring sin acoplar sus modelos al framework. */
@Configuration(proxyBeanMethods = false)
public class DominioConfiguration {
    @Bean
    CatalogoProductos catalogoProductos() { return new CatalogoProductos(); }

    @Bean
    ClienteService clienteService() { return new ClienteService(); }

    @Bean
    PedidoService pedidoService(CatalogoProductos catalogo, ClienteService clientes) {
        return new PedidoService(catalogo, clientes);
    }
}

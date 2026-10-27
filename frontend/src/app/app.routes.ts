import { Routes } from '@angular/router';
import { DashboardPage } from './pages/dashboard';
import { PedidosPage } from './pages/pedidos';
import { InventarioPage } from './pages/inventario';
import { RecursosPage } from './pages/recursos';
import { PlanificacionPage } from './pages/planificacion';
import { DeadlockPage } from './pages/deadlock';

export const routes: Routes = [
  { path: '', component: DashboardPage }, { path: 'pedidos', component: PedidosPage },
  { path: 'inventario', component: InventarioPage }, { path: 'recursos', component: RecursosPage },
  { path: 'planificacion', component: PlanificacionPage }, { path: 'deadlock', component: DeadlockPage },
  { path: '**', redirectTo: '' },
];

import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { LoginComponent } from './auth/login.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', canActivate: [authGuard], loadComponent: () => import('./home.component').then(m => m.HomeComponent) },
  { path: '**', redirectTo: '' },
];

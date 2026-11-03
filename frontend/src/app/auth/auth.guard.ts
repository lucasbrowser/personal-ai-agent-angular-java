import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService); const router = inject(Router);
  if (auth.token()) return true;
  return auth.refresh().pipe(map(ok => ok ? true : router.createUrlTree(['/login'])));
};

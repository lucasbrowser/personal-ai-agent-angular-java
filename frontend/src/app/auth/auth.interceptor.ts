import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const isAuthEndpoint = request.url.includes('/auth/login') || request.url.includes('/auth/refresh') || request.url.includes('/auth/logout');
  const token = auth.token();
  const outgoing = token && !isAuthEndpoint ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
  return next(outgoing).pipe(catchError((error: unknown) => {
    if (!(error instanceof HttpErrorResponse) || error.status !== 401 || isAuthEndpoint || !token) return throwError(() => error);
    return auth.refresh().pipe(switchMap(ok => {
      if (!ok) { void router.navigate(['/login']); return throwError(() => error); }
      const retry = request.clone({ setHeaders: { Authorization: `Bearer ${auth.token()}` } });
      return next(retry);
    }));
  }));
};

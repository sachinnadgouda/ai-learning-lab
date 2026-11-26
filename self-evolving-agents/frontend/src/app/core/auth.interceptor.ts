import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();
  const outgoing = token && req.url.startsWith('/api/') ? req.clone({ setHeaders: { 'X-Demo-Admin': token } }) : req;
  return next(outgoing).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && token) {
        auth.logout();
        void router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};

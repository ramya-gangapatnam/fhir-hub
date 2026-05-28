import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { RUNTIME_CONFIG } from './runtime-config';

/**
 * Attaches the static shared bearer token (from runtime config) to every
 * outgoing request. Principle VI: the token is supplied at runtime, never
 * hard-coded into business logic.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const { authToken } = inject(RUNTIME_CONFIG);
  return next(
    req.clone({
      setHeaders: { Authorization: `Bearer ${authToken}` },
    }),
  );
};

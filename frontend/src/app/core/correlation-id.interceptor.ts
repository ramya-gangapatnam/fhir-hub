import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Generates a fresh UUID per request and sends it as X-Correlation-Id so the
 * backend can stitch the request into its end-to-end trace. Principle VII:
 * every client request is correlatable from the moment it leaves the SPA.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (req, next) => {
  return next(
    req.clone({
      setHeaders: { 'X-Correlation-Id': crypto.randomUUID() },
    }),
  );
};

import { InjectionToken } from '@angular/core';

/**
 * App-wide runtime configuration. Values are resolved at app start rather than
 * baked into the bundle, so the backend URL and bearer token can be overridden
 * (e.g. per deploy environment) without rebuilding the SPA.
 */
export interface RuntimeConfig {
  /** Base URL of the FHIR Hub backend, no trailing slash. */
  apiBaseUrl: string;
  /** Static shared bearer token. Demo-only default; override in real deploys. */
  authToken: string;
}

const DEFAULT_CONFIG: RuntimeConfig = {
  apiBaseUrl: 'http://localhost:8080',
  authToken: 'dev-only-do-not-ship',
};

/** Optional runtime override, set on the global before the app bootstraps. */
const OVERRIDE_KEY = '__FHIR_HUB_CONFIG__';

export function loadRuntimeConfig(): RuntimeConfig {
  const global = globalThis as unknown as Record<string, unknown>;
  const override = global[OVERRIDE_KEY] as Partial<RuntimeConfig> | undefined;
  return { ...DEFAULT_CONFIG, ...(override ?? {}) };
}

export const RUNTIME_CONFIG = new InjectionToken<RuntimeConfig>('RUNTIME_CONFIG', {
  providedIn: 'root',
  factory: loadRuntimeConfig,
});

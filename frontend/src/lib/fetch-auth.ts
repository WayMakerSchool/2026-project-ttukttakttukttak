/**
 * Global fetch interceptor that auto-attaches identity headers (X-Client-Id
 * + Bearer token) to every request hitting our backend.
 *
 * Why: the backend middleware enforces book ownership on every /books/{id}/*
 * request. Without these headers, requests come through as "anonymous with
 * no client-id" and the middleware would 404 even the owner's own book.
 * Touching every fetch call site to thread headers manually is brittle —
 * one missed call leaks or breaks silently. A single global patch
 * guarantees consistency.
 */
import { loadUser } from "./auth";
import { getClientId } from "./client-id";

// Paths that need identity headers. Anything else (Spline iframes, external
// fonts) passes through unchanged.
const PROTECTED_PREFIXES = ["/books", "/me", "/dict", "/covers", "/upload", "/camera"];

function needsAuth(url: string): boolean {
  return PROTECTED_PREFIXES.some((p) => url === p || url.startsWith(p + "/") || url.startsWith(p + "?"));
}

const originalFetch = window.fetch.bind(window);

window.fetch = (input, init) => {
  // Resolve the URL string regardless of which fetch input form is used.
  let url: string;
  if (typeof input === "string") url = input;
  else if (input instanceof URL) url = input.pathname + input.search;
  else url = input.url;

  // Normalize absolute URLs into pathnames so the prefix check works for
  // both `/books/...` and `http://localhost:8000/books/...`.
  try {
    if (/^https?:/.test(url)) {
      const u = new URL(url);
      // Only auto-attach for same-origin (or proxied) calls — never leak
      // tokens to a third-party host.
      if (u.origin !== location.origin) return originalFetch(input, init);
      url = u.pathname + u.search;
    }
  } catch {
    // Fall through; treat as-is.
  }

  if (!needsAuth(url)) return originalFetch(input, init);

  const opts: RequestInit = init ? { ...init } : {};
  const headers = new Headers(opts.headers || {});
  if (!headers.has("X-Client-Id")) {
    headers.set("X-Client-Id", getClientId());
  }
  if (!headers.has("Authorization")) {
    const u = loadUser();
    if (u?.idToken) headers.set("Authorization", `Bearer ${u.idToken}`);
  }
  opts.headers = headers;
  return originalFetch(input, opts);
};

export type Coords = { lat: number; lon: number };

/**
 * Best-effort browser geolocation. Resolves to coords or null — it NEVER
 * rejects, so callers can `await getGeo()` without a try/catch.
 *
 * Used only to tint the background music ~10% toward the reader's
 * place / weather / season. If the user denies permission, the device has no
 * GPS, or it times out, we resolve null and the app behaves exactly as before
 * (music driven 100% by the book's mood).
 */
export function getGeo(timeoutMs = 6000): Promise<Coords | null> {
  return new Promise((resolve) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      resolve(null);
      return;
    }
    let settled = false;
    const finish = (value: Coords | null) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    navigator.geolocation.getCurrentPosition(
      (pos) => finish({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
      () => finish(null),
      { enableHighAccuracy: false, timeout: timeoutMs, maximumAge: 600000 },
    );
    // Safety net: some browsers never invoke either callback if the prompt is
    // dismissed in an unusual way.
    window.setTimeout(() => finish(null), timeoutMs + 500);
  });
}

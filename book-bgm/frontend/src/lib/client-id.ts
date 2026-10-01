const KEY = "book_store_client_id";

export function getClientId(): string {
  let id = localStorage.getItem(KEY);
  if (!id) {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      id = crypto.randomUUID();
    } else {
      id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    }
    localStorage.setItem(KEY, id);
  }
  return id;
}

export function progressHeaders(): Record<string, string> {
  return { "X-Client-Id": getClientId() };
}

/** Build the `?cid=...&token=...` suffix used to authenticate static URLs
 *  (PDF embeds, <img src=>, WebSocket) where custom headers can't be set.
 *  Returns "?cid=..." prefix so callers can concatenate directly. */
export function authQuery(idToken?: string | null): string {
  const params = new URLSearchParams();
  params.set("cid", getClientId());
  if (idToken) params.set("token", idToken);
  return `?${params.toString()}`;
}

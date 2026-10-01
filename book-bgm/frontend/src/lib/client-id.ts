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

export const GOOGLE_CLIENT_ID =
  "799290215581-m1uo6jiai4na2ohfba9vektu84gv05kp.apps.googleusercontent.com";

export interface AuthUser {
  email: string;
  name: string;
  picture: string;
  idToken: string;
}

const STORAGE_KEY = "book_store_user";

export function loadUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const u = JSON.parse(raw) as AuthUser;
    // Reject obviously expired tokens (decoded `exp` is unix seconds).
    if (decodeJwtExp(u.idToken) < Math.floor(Date.now() / 1000) + 30) {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return u;
  } catch {
    return null;
  }
}

export function saveUser(user: AuthUser | null) {
  if (user) localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
  else localStorage.removeItem(STORAGE_KEY);
}

/** Decode a JWT payload client-side. NOT a verification — just used to pull
 *  display fields. The backend always re-verifies signatures. */
export function decodeJwt(token: string): Record<string, unknown> {
  try {
    const payload = token.split(".")[1];
    const padded = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = atob(padded);
    return JSON.parse(json);
  } catch {
    return {};
  }
}

function decodeJwtExp(token: string): number {
  const claims = decodeJwt(token);
  return typeof claims.exp === "number" ? claims.exp : 0;
}

export function authHeaders(user: AuthUser | null): Record<string, string> {
  return user ? { Authorization: `Bearer ${user.idToken}` } : {};
}

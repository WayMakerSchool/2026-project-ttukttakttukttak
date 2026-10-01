import { useEffect, useRef, useState } from "react";
import { GOOGLE_CLIENT_ID, decodeJwt, saveUser, type AuthUser } from "../lib/auth";

interface Props {
  user: AuthUser | null;
  onChange: (user: AuthUser | null) => void;
}

interface GoogleCredentialResponse {
  credential: string;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyWindow = any;

function waitForGoogle(): Promise<AnyWindow> {
  return new Promise((resolve) => {
    const w = window as AnyWindow;
    if (w.google?.accounts?.id) {
      resolve(w);
      return;
    }
    const tick = window.setInterval(() => {
      const ww = window as AnyWindow;
      if (ww.google?.accounts?.id) {
        clearInterval(tick);
        resolve(ww);
      }
    }, 150);
  });
}

export function AuthButton({ user, onChange }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (user) return;
    let cancelled = false;
    let cleanupRoot: HTMLDivElement | null = null;

    (async () => {
      const w = await waitForGoogle();
      if (cancelled) return;
      const target = containerRef.current;
      if (!target) return;
      target.innerHTML = "";
      cleanupRoot = target;

      w.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        callback: (resp: GoogleCredentialResponse) => {
          const claims = decodeJwt(resp.credential);
          const u: AuthUser = {
            email: (claims.email as string) || "",
            name: (claims.name as string) || (claims.email as string) || "익명",
            picture: (claims.picture as string) || "",
            idToken: resp.credential,
          };
          saveUser(u);
          onChange(u);
        },
        auto_select: false,
        ux_mode: "popup",
      });

      // Icon-only round Google button — keeps the floating auth control
      // narrow enough to not collide with the reader/library header brand
      // on mobile. The G logo alone is universally recognized so we don't
      // need the "Google 계정으로 로그인" label.
      w.google.accounts.id.renderButton(target, {
        type: "icon",
        theme: "filled_black",
        size: "medium",
        shape: "circle",
      });
    })();

    return () => {
      cancelled = true;
      if (cleanupRoot) cleanupRoot.innerHTML = "";
    };
  }, [user, onChange]);

  if (!user) {
    return <div ref={containerRef} className="auth-signin" />;
  }

  function signOut() {
    const w = window as AnyWindow;
    try {
      w.google?.accounts?.id?.disableAutoSelect?.();
    } catch {
      /* ignore */
    }
    saveUser(null);
    onChange(null);
    setMenuOpen(false);
  }

  return (
    <div className="auth-profile">
      <button
        type="button"
        className="auth-profile-trigger"
        onClick={() => setMenuOpen((v) => !v)}
        aria-expanded={menuOpen}
        title={user.email}
      >
        {user.picture ? (
          <img src={user.picture} alt="" referrerPolicy="no-referrer" />
        ) : (
          <span className="auth-profile-fallback">{(user.name || "?")[0]}</span>
        )}
      </button>
      {menuOpen && (
        <>
          <div
            className="mode-menu-backdrop"
            onClick={() => setMenuOpen(false)}
            aria-hidden="true"
          />
          <div className="mode-menu auth-profile-menu" role="menu">
            <div className="auth-profile-info">
              <div className="auth-profile-name">{user.name}</div>
              <div className="auth-profile-email">{user.email}</div>
            </div>
            <button
              type="button"
              className="mode-menu-item"
              onClick={signOut}
              role="menuitem"
            >
              <span className="mode-menu-label">로그아웃</span>
            </button>
          </div>
        </>
      )}
    </div>
  );
}

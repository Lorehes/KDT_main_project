// [목적] 다중 탭 인증 동기화 — 한 탭의 로그아웃이 다른 탭에 즉시 반영
// [이유] httpOnly 쿠키는 삭제되면 모든 탭에서 유효하지 않지만 각 탭의 Zustand 상태는 독립적.
//   탭 A에서 로그아웃해도 탭 B는 여전히 "로그인됨" UI 상태를 유지 → BroadcastChannel로 동기화.
//   iOS Safari 구버전(< 15.4) BroadcastChannel 미지원 → localStorage storage event 폴백.
// [사이드 임팩트] authStore.logout()에서 broadcastAuth({ type: "logout" })를 호출해야 효과 있음.
//   subscribeAuthBroadcast()는 최초 렌더 시 effect 안에서 호출하고 cleanup(반환 함수)으로 제거.
//   SSR(typeof window === "undefined") 환경에서는 no-op — 서버 컴포넌트에서 안전하게 import 가능.
// [수정 시 고려사항] "refresh" 메시지 타입은 향후 RTR(Refresh Token Rotation) 다중 탭 경쟁 차단에 사용.
//   BroadcastChannel은 동일 origin의 탭 간에만 동작. 다른 도메인(서브도메인 포함) 탭은 제외.
//   localStorage 폴백은 set + 즉시 remove 패턴으로 storage event를 발생시킴.

const CHANNEL_NAME = "dr_auth" as const;

type AuthMessage = { type: "logout" } | { type: "refresh" };

function createChannel(): BroadcastChannel | null {
  if (typeof window === "undefined") return null;
  if (typeof BroadcastChannel === "undefined") return null;
  return new BroadcastChannel(CHANNEL_NAME);
}

function postViaLocalStorage(msg: AuthMessage): void {
  if (typeof window === "undefined") return;
  // set 후 즉시 remove → storage event 발생 (폴백용)
  window.localStorage.setItem(CHANNEL_NAME, JSON.stringify({ ...msg, ts: Date.now() }));
  window.localStorage.removeItem(CHANNEL_NAME);
}

/** 모든 탭에 인증 이벤트를 전파. 로그아웃 후 authStore.logout() 호출 전에 사용. */
export function broadcastAuth(msg: AuthMessage): void {
  const ch = createChannel();
  if (ch) {
    ch.postMessage(msg);
    ch.close();
  } else {
    postViaLocalStorage(msg);
  }
}

/**
 * 다른 탭의 인증 이벤트를 구독. cleanup 함수를 반환하므로 useEffect에서 반드시 호출.
 * @example
 * useEffect(() => subscribeAuthBroadcast(() => { setUser(null); router.push("/login"); }), []);
 */
export function subscribeAuthBroadcast(onLogout: () => void): () => void {
  if (typeof window === "undefined") return () => {};

  const ch = createChannel();

  if (ch) {
    const handler = (e: MessageEvent<AuthMessage>) => {
      if (e.data.type === "logout") onLogout();
    };
    ch.addEventListener("message", handler);
    return () => {
      ch.removeEventListener("message", handler);
      ch.close();
    };
  }

  // iOS Safari 폴백 — localStorage storage event
  const storageHandler = (e: StorageEvent) => {
    if (e.key !== CHANNEL_NAME || !e.newValue) return;
    try {
      const msg: AuthMessage & { ts?: number } = JSON.parse(e.newValue);
      if (msg.type === "logout") onLogout();
    } catch {
      // 잘못된 JSON은 무시
    }
  };
  window.addEventListener("storage", storageHandler);
  return () => window.removeEventListener("storage", storageHandler);
}

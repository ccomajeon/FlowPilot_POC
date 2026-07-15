import { afterEach, describe, expect, it, vi } from "vitest";
import { api, onAuthExpired } from "./api";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const todo = {
  id: "todo-1",
  title: "계약 검토",
  description: null,
  status: "OPEN",
  dueDate: null,
  version: 1,
  createdAt: "2026-07-15T00:00:00Z",
  updatedAt: "2026-07-15T00:00:00Z"
};

afterEach(() => vi.unstubAllGlobals());

describe("api", () => {
  it("CSRF 토큰이 없으면 변경 요청을 네트워크 전에 차단한다", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ authenticated: true, user: { id: "u1", displayName: "사용자" } }));
    vi.stubGlobal("fetch", fetchMock);
    await api.session();

    await expect(api.create({ title: "새 할 일" })).rejects.toMatchObject({ code: "CSRF_TOKEN_MISSING" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("동시 401은 세션을 한 번만 재확인하고 인증 만료를 한 번 알린다", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "u1", displayName: "사용자" }, csrfToken: "csrf" }))
      .mockResolvedValueOnce(json({}, 401))
      .mockResolvedValueOnce(json({}, 401))
      .mockResolvedValueOnce(json({}, 401));
    vi.stubGlobal("fetch", fetchMock);
    await api.session();
    const expired = vi.fn();
    const unsubscribe = onAuthExpired(expired);

    const results = await Promise.allSettled([
      api.todos({ status: "ALL", query: "" }),
      api.todos({ status: "OPEN", query: "" })
    ]);

    expect(results.every((result) => result.status === "rejected")).toBe(true);
    expect(fetchMock.mock.calls.filter(([url]) => url === "/auth/session")).toHaveLength(2);
    expect(expired).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it("잘못된 Todo enum을 UI 상태로 반환하지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ items: [{ ...todo, status: "UNKNOWN" }] })));

    await expect(api.todos({ status: "ALL", query: "" })).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("잘못된 version과 날짜를 계약 오류로 변환한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      items: [{ ...todo, version: "1", updatedAt: "not-a-date" }]
    })));

    await expect(api.todos({ status: "ALL", query: "" })).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
      message: "서버 응답 형식이 올바르지 않습니다."
    });
  });
});

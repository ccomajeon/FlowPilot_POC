import { afterEach, describe, expect, it, vi } from "vitest";
import { api, onAuthExpired, userMessage } from "./api";

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

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

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
      api.todos({ status: "ALL", query: "", due: "ALL", sort: "updatedAt:desc" }),
      api.todos({ status: "OPEN", query: "", due: "ALL", sort: "updatedAt:desc" })
    ]);

    expect(results.every((result) => result.status === "rejected")).toBe(true);
    expect(fetchMock.mock.calls.filter(([url]) => url === "/auth/session")).toHaveLength(2);
    expect(expired).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it("잘못된 Todo enum을 UI 상태로 반환하지 않는다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ items: [{ ...todo, status: "UNKNOWN" }] })));

    await expect(api.todos({ status: "ALL", query: "", due: "ALL", sort: "updatedAt:desc" })).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("잘못된 version과 날짜를 계약 오류로 변환한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      items: [{ ...todo, version: "1", updatedAt: "not-a-date" }]
    })));

    await expect(api.todos({ status: "ALL", query: "", due: "ALL", sort: "updatedAt:desc" })).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
      message: "서버 응답 형식이 올바르지 않습니다."
    });
  });

  it("검증되지 않은 오류 메타데이터를 사용자 메시지에서 제거한다", async () => {
    expect.assertions(3);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      code: "INVALID CODE",
      traceId: "secret=<unsafe-value>"
    }, 403)));

    try {
      await api.todos({ status: "ALL", query: "" });
    } catch (error) {
      expect(error).toMatchObject({ code: "REQUEST_FAILED", traceId: undefined });
      expect(userMessage(error)).not.toContain("secret");
      expect(userMessage(error)).toBe("요청한 작업을 수행할 권한이 없습니다.");
    }
  });

  it.each([
    ["2026-02-29", "2026-07-15T00:00:00Z"],
    ["2026-99-99", "2026-07-15T00:00:00Z"],
    [null, "2026-07-15T00:00:00"],
    [null, "2026-02-30T00:00:00Z"]
  ])("실재하지 않는 날짜와 시간대 없는 instant를 거부한다", async (dueDate, createdAt) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      items: [{ ...todo, dueDate, createdAt }]
    })));

    await expect(api.todos({ status: "ALL", query: "", due: "ALL", sort: "updatedAt:desc" }))
      .rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });

  it("윤년 날짜와 UTC 및 offset instant를 허용한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      items: [{
        ...todo,
        dueDate: "2028-02-29",
        createdAt: "2026-07-15T09:30:00+09:00",
        updatedAt: "2026-07-15T00:30:00.123Z"
      }]
    })));

    await expect(api.todos({ status: "ALL", query: "", due: "ALL", sort: "updatedAt:desc" }))
      .resolves.toMatchObject({ items: [{ dueDate: "2028-02-29" }] });
  });

  it("기한과 정렬 query를 API 계약대로 전송한다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ items: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await api.todos({ status: "OPEN", query: "검토", due: "OVERDUE", sort: "createdAt:desc" });

    const url = new URL(String(fetchMock.mock.calls[0][0]), "https://example.test");
    expect(url.searchParams.get("status")).toBe("OPEN");
    expect(url.searchParams.get("q")).toBe("검토");
    expect(url.searchParams.get("due")).toBe("OVERDUE");
    expect(url.searchParams.get("sort")).toBe("createdAt:desc");
  });

  it("GET 503을 제한된 backoff 후 복구한다", async () => {
    vi.useFakeTimers();
    vi.spyOn(Math, "random").mockReturnValue(0);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({}, 503))
      .mockResolvedValueOnce(json({}, 502))
      .mockResolvedValueOnce(json({ items: [todo] }));
    vi.stubGlobal("fetch", fetchMock);

    const result = api.todos({ status: "ALL", query: "", due: "ALL", sort: "updatedAt:desc" });
    await vi.runAllTimersAsync();

    await expect(result).resolves.toMatchObject({ items: [{ id: "todo-1" }] });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("생략된 기한과 정렬에 API 기본값을 적용한다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ items: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await api.todos({ status: "ALL", query: "" });

    const url = new URL(String(fetchMock.mock.calls[0][0]), "https://example.test");
    expect(url.searchParams.get("sort")).toBe("updatedAt:desc");
    expect(url.searchParams.has("due")).toBe(false);
  });

  it("Retry-After 초 값을 다음 조회 지연에 반영한다", async () => {
    vi.useFakeTimers();
    const onRetry = vi.fn();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("{}", { status: 429, headers: { "Retry-After": "2" } }))
      .mockResolvedValueOnce(json({ items: [] }));
    vi.stubGlobal("fetch", fetchMock);

    const result = api.todos(
      { status: "ALL", query: "" },
      undefined,
      undefined,
      onRetry
    );
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);

    await expect(result).resolves.toEqual({ items: [], nextCursor: undefined });
    expect(onRetry).toHaveBeenCalledWith({ attempt: 1, delayMs: 2000 });
  });

  it("AbortSignal이 재시도 대기를 즉시 취소한다", async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const fetchMock = vi.fn().mockResolvedValue(json({}, 503));
    vi.stubGlobal("fetch", fetchMock);

    const result = api.todos({ status: "ALL", query: "" }, controller.signal);
    await Promise.resolve();
    controller.abort();

    await expect(result).rejects.toMatchObject({ name: "AbortError" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

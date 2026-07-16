import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const openTodo = {
  id: "todo-1",
  title: "계약 검토",
  description: null,
  status: "OPEN" as const,
  dueDate: null,
  version: 1,
  createdAt: "2026-07-15T00:00:00Z",
  updatedAt: "2026-07-15T00:00:00Z"
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("App", () => {
  it("미인증 사용자를 로그인 화면으로 보호한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ authenticated: false, user: null })));
    render(<App />);
    expect(await screen.findByRole("heading", { name: /오늘 할 일을/ })).toBeInTheDocument();
    expect(screen.queryByText("계약 검토")).not.toBeInTheDocument();
  });

  it("목록을 조회하고 완료 상태를 변경한다", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo] }))
      .mockResolvedValueOnce(json({ ...openTodo, status: "DONE", version: 2 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    const toggle = await screen.findByRole("button", { name: "계약 검토 완료로 변경" });
    await userEvent.click(toggle);

    await waitFor(() => expect(screen.getByRole("button", { name: "계약 검토 미완료로 변경" })).toBeInTheDocument());
    const update = fetchMock.mock.calls[2];
    expect(update[0]).toBe("/api/v1/todos/todo-1");
    expect((update[1] as RequestInit).headers).toBeInstanceOf(Headers);
    expect(((update[1] as RequestInit).headers as Headers).get("If-Match")).toBe("1");
    expect(((update[1] as RequestInit).headers as Headers).get("X-CSRF-Token")).toBe("test-csrf");
  });

  it("생성 실패 후 입력을 유지하고 안전한 오류를 알린다", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [] }))
      .mockResolvedValueOnce(json({ message: "internal stack", traceId: "trace-safe" }, 500));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    const input = await screen.findByRole("textbox", { name: "새 할 일 제목" });
    await userEvent.type(input, "보존할 입력");
    await userEvent.click(screen.getByRole("button", { name: "추가" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("서비스가 일시적으로 불안정합니다."));
    expect(input).toHaveValue("보존할 입력");
    expect(screen.queryByText("internal stack")).not.toBeInTheDocument();
  });

  it("OPEN 항목을 완료하면 현재 목록에서 제거한다", async () => {
    window.history.replaceState(null, "", "/todos?status=OPEN");
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo] }))
      .mockResolvedValueOnce(json({ ...openTodo, status: "DONE", version: 2 })));
    render(<App />);

    await userEvent.click(await screen.findByRole("button", { name: "계약 검토 완료로 변경" }));
    await waitFor(() => expect(screen.queryByText("계약 검토")).not.toBeInTheDocument());
  });

  it("커서 페이지를 더 보기 요청으로 점진적으로 병합한다", async () => {
    const secondTodo = { ...openTodo, id: "todo-2", title: "배포 확인" };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo], nextCursor: "page-2" }))
      .mockResolvedValueOnce(json({ items: [openTodo, secondTodo], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    expect(await screen.findByText("계약 검토")).toBeInTheDocument();
    expect(screen.queryByText("배포 확인")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "더 보기" }));
    expect(await screen.findByText("배포 확인")).toBeInTheDocument();
    expect(screen.getByText("2개")).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[2][0])).toContain("cursor=page-2");
  });

  it("기한과 정렬 query를 복원하고 잘못된 query를 정규화한다", async () => {
    window.history.replaceState(null, "", "/todos?status=INVALID&due=OVERDUE&sort=createdAt%3Adesc&ignored=1");
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [] }));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    expect(await screen.findByRole("heading", { name: "조건에 맞는 할 일이 없습니다" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "기한 필터" })).toHaveValue("OVERDUE");
    expect(screen.getByRole("combobox", { name: "정렬" })).toHaveValue("createdAt:desc");
    await waitFor(() => expect(window.location.search).toBe("?due=OVERDUE&sort=createdAt%3Adesc"));
    expect(String(fetchMock.mock.calls[1][0])).toContain("due=OVERDUE");
    expect(String(fetchMock.mock.calls[1][0])).toContain("sort=createdAt%3Adesc");
  });

  it("생성 성공 응답을 반영하고 요청 body와 멱등성 헤더를 전송한다", async () => {
    const created = { ...openTodo, id: "todo-created", title: "새 작업" };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [] }))
      .mockResolvedValueOnce(json(created));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    const input = await screen.findByRole("textbox", { name: "새 할 일 제목" });
    await userEvent.type(input, "  새 작업  ");
    await userEvent.click(screen.getByRole("button", { name: "추가" }));

    expect(await screen.findByText("새 작업")).toBeInTheDocument();
    expect(input).toHaveValue("");
    expect(screen.getByRole("status")).not.toHaveClass("sr-only");
    const request = fetchMock.mock.calls[2][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toMatchObject({ title: "새 작업" });
    expect((request.headers as Headers).get("Idempotency-Key")).toBeTruthy();
    expect((request.headers as Headers).get("X-CSRF-Token")).toBe("test-csrf");
  });

  it("로그아웃 실패 시 보호 데이터를 제거하고 재시도 경로를 제공한다", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo] }))
      .mockRejectedValueOnce(new TypeError("network")));
    render(<App />);

    await userEvent.click(await screen.findByRole("button", { name: "로그아웃" }));
    expect(await screen.findByRole("button", { name: "서버 로그아웃 다시 시도" })).toBeInTheDocument();
    expect(screen.queryByText("계약 검토")).not.toBeInTheDocument();
  });

  it.each([
    ["TODAY", "2026-07-15", true],
    ["TODAY", "2026-07-16", false],
    ["OVERDUE", "2026-07-14", true],
    ["OVERDUE", "2026-07-15", false],
    ["UPCOMING", "2026-07-16", true],
    ["UPCOMING", "2026-07-14", false]
  ] as const)("%s 필터에서 생성 응답의 기한 %s 표시 여부를 판정한다", async (due, dueDate, visible) => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(2026, 6, 15, 12, 0, 0));
    window.history.replaceState(null, "", `/todos?due=${due}`);
    const created = { ...openTodo, id: "created", title: "필터 생성 항목", dueDate };
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [] }))
      .mockResolvedValueOnce(json(created)));
    render(<App />);

    const input = await screen.findByRole("textbox", { name: "새 할 일 제목" });
    await userEvent.click(input);
    await userEvent.keyboard("필터 생성 항목");
    await userEvent.click(screen.getByRole("button", { name: "추가" }));

    await waitFor(() => {
      if (visible) expect(screen.getByText("필터 생성 항목")).toBeInTheDocument();
      else expect(screen.queryByText("필터 생성 항목")).not.toBeInTheDocument();
    });
  });

  it("변경 없는 편집은 즉시 닫고 변경된 편집은 확인 후 포커스를 복원한다", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo] })));
    render(<App />);

    const edit = await screen.findByRole("button", { name: "계약 검토 수정" });
    await userEvent.click(edit);
    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(confirm).not.toHaveBeenCalled();
    expect(edit).toHaveFocus();

    await userEvent.click(edit);
    const title = screen.getByRole("textbox", { name: "제목" });
    await userEvent.type(title, " 변경");
    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(title).toBeInTheDocument();

    confirm.mockReturnValue(true);
    await userEvent.click(screen.getByRole("button", { name: "취소" }));
    await waitFor(() => expect(edit).toHaveFocus());
    expect(screen.queryByRole("textbox", { name: "제목" })).not.toBeInTheDocument();
  });

  it("저장하지 않은 편집이 있으면 로그아웃을 취소할 수 있다", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo] }));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    await userEvent.click(await screen.findByRole("button", { name: "계약 검토 수정" }));
    await userEvent.type(screen.getByRole("textbox", { name: "제목" }), " 변경");
    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    expect(confirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("textbox", { name: "제목" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("중간 페이지 조회 실패 시 이미 표시한 항목을 보존한다", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo], nextCursor: "page-2" }))
      .mockResolvedValue(json({}, 500)));
    render(<App />);

    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(await screen.findByText("계약 검토")).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("필터 변경 시 진행 중인 추가 조회 상태와 오래된 cursor 응답을 폐기한다", async () => {
    let resolveOldPage!: (response: Response) => void;
    const oldPage = new Promise<Response>((resolve) => {
      resolveOldPage = resolve;
    });
    const searchResult = { ...openTodo, id: "todo-search", title: "검색 결과" };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo], nextCursor: "page-2" }))
      .mockReturnValueOnce(oldPage)
      .mockResolvedValueOnce(json({ items: [searchResult], nextCursor: "search-page-2" }));
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    await userEvent.type(screen.getByRole("searchbox", { name: "제목 검색" }), "검색");

    expect(await screen.findByText("검색 결과")).toBeInTheDocument();
    resolveOldPage(json({
      items: [{ ...openTodo, id: "todo-old-page", title: "이전 페이지" }],
      nextCursor: null
    }));

    await waitFor(() => expect(screen.getByRole("button", { name: "더 보기" })).toBeEnabled());
    expect(screen.queryByText("이전 페이지")).not.toBeInTheDocument();
  });

  it("서버가 동일 cursor를 반복하면 기존 목록을 보존하고 중단한다", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items: [openTodo], nextCursor: "page-2" }))
      .mockResolvedValueOnce(json({ items: [], nextCursor: "page-2" })));
    render(<App />);

    await userEvent.click(await screen.findByRole("button", { name: "더 보기" }));
    expect(await screen.findByText("페이지 응답이 올바르지 않습니다.")).toBeInTheDocument();
    expect(screen.getByText("계약 검토")).toBeInTheDocument();
  });

  it("500항목 한도에서 후속 페이지 요청을 차단한다", async () => {
    const items = Array.from({ length: 500 }, (_, index) => ({
      ...openTodo,
      id: `todo-${index}`,
      title: `항목 ${index}`
    }));
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }))
      .mockResolvedValueOnce(json({ items, nextCursor: "page-2" })));
    render(<App />);

    expect(await screen.findByText("500개")).toBeInTheDocument();
    expect(screen.getByText("표시 가능한 목록 한도에 도달했습니다. 필터를 좁혀 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "더 보기" })).not.toBeInTheDocument();
  });

  it("20페이지 한도에서 후속 cursor를 폐기한다", async () => {
    let page = 0;
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === "/auth/session") {
        return Promise.resolve(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" }, csrfToken: "test-csrf" }));
      }
      page += 1;
      return Promise.resolve(json({
        items: [{ ...openTodo, id: `todo-${page}`, title: `페이지 ${page}` }],
        nextCursor: `page-${page + 1}`
      }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    expect(await screen.findByText("1개")).toBeInTheDocument();
    for (let expected = 2; expected <= 20; expected += 1) {
      await userEvent.click(screen.getByRole("button", { name: "더 보기" }));
      expect(await screen.findByText(`${expected}개`)).toBeInTheDocument();
    }
    expect(screen.getByText("표시 가능한 목록 한도에 도달했습니다. 필터를 좁혀 주세요.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "더 보기" })).not.toBeInTheDocument();
  });
});

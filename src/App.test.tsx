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
});

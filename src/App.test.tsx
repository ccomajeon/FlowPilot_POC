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

afterEach(() => vi.unstubAllGlobals());

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
      .mockResolvedValueOnce(json({ authenticated: true, user: { id: "user-1", displayName: "사용자" } }))
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
});

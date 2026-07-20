import { render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ErrorBoundary } from "./ErrorBoundary";

afterEach(() => {
  vi.restoreAllMocks();
});

it("렌더 오류의 세부정보를 노출하지 않고 복구 경로를 제공한다", () => {
  vi.spyOn(console, "error").mockImplementation(() => undefined);

  function BrokenView(): never {
    throw new Error("할 일 본문과 내부 stack");
  }

  render(
    <ErrorBoundary>
      <BrokenView />
    </ErrorBoundary>
  );

  const alert = screen.getByRole("alert");
  expect(alert).toHaveTextContent("화면을 표시하지 못했습니다.");
  expect(alert).not.toHaveTextContent("할 일 본문");
  expect(screen.getByRole("button", { name: "새로고침" })).toBeInTheDocument();
});

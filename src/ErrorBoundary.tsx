import { Component, ReactNode } from "react";

type ErrorBoundaryProps = { children: ReactNode };
type ErrorBoundaryState = { failed: boolean };

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true };
  }

  componentDidCatch(): void {
    // Intentionally avoid console logging because render errors can contain user data.
    // A production error collector may be connected here only after redaction is approved.
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children;

    return (
      <main className="fatal-shell">
        <section className="fatal-card" role="alert" aria-labelledby="fatal-title">
          <div className="brand-mark" aria-hidden="true">!</div>
          <p className="eyebrow">FLOW TODO</p>
          <h1 id="fatal-title">화면을 표시하지 못했습니다.</h1>
          <p className="muted">입력 내용이나 오류 세부정보는 전송하지 않았습니다. 새로고침 후 다시 시도해 주세요.</p>
          <button className="primary" onClick={() => window.location.reload()}>새로고침</button>
        </section>
      </main>
    );
  }
}

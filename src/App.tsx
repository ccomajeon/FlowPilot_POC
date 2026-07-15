import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { api, Session, Todo, TodoInput, userMessage } from "./api";

type Filter = "ALL" | "OPEN" | "DONE";

function readFilters(): { status: Filter; query: string } {
  const params = new URLSearchParams(window.location.search);
  const value = params.get("status");
  return {
    status: value === "OPEN" || value === "DONE" ? value : "ALL",
    query: params.get("q")?.slice(0, 100) ?? ""
  };
}

function LoginPage({ error }: { error?: string }) {
  return (
    <main className="login-shell">
      <section className="login-card" aria-labelledby="login-title">
        <div className="brand-mark" aria-hidden="true">✓</div>
        <p className="eyebrow">FLOW TODO</p>
        <h1 id="login-title">오늘 할 일을<br />가볍게 정리하세요.</h1>
        <p className="muted">안전한 OAuth 로그인으로 어디서든 나만의 할 일에 접근할 수 있습니다.</p>
        {error && <p className="alert error" role="alert">{error}</p>}
        <button className="primary wide" onClick={() => api.login()}>OAuth로 계속하기</button>
        <p className="legal">계속하면 서비스 이용약관과 개인정보처리방침에 동의하게 됩니다.</p>
      </section>
    </main>
  );
}

function TodoEditor({
  todo,
  onSave,
  onCancel
}: {
  todo?: Todo;
  onSave: (input: TodoInput) => Promise<void>;
  onCancel?: () => void;
}) {
  const [title, setTitle] = useState(todo?.title ?? "");
  const [description, setDescription] = useState(todo?.description ?? "");
  const [dueDate, setDueDate] = useState(todo?.dueDate ?? "");
  const [saving, setSaving] = useState(false);
  const [fieldError, setFieldError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    const cleanTitle = title.trim();
    if (!cleanTitle) {
      setFieldError("제목을 입력하세요.");
      return;
    }
    if (cleanTitle.length > 200) {
      setFieldError("제목은 200자 이하로 입력하세요.");
      return;
    }
    setSaving(true);
    setFieldError("");
    try {
      await onSave({
        title: cleanTitle,
        description: description.trim() || null,
        dueDate: dueDate || null
      });
      if (!todo) {
        setTitle("");
        setDescription("");
        setDueDate("");
      }
    } catch { /* Parent reports the sanitized request error. */ } finally {
      setSaving(false);
    }
  }

  return (
    <form className={todo ? "edit-form" : "quick-add"} onSubmit={submit}>
      <div className="field grow">
        <label htmlFor={todo ? `title-${todo.id}` : "new-title"}>
          {todo ? "제목" : <span className="sr-only">새 할 일 제목</span>}
        </label>
        <input
          id={todo ? `title-${todo.id}` : "new-title"}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="무엇을 해야 하나요?"
          maxLength={200}
          aria-describedby={fieldError ? `error-${todo?.id ?? "new"}` : undefined}
        />
        {fieldError && <span id={`error-${todo?.id ?? "new"}`} className="field-error">{fieldError}</span>}
      </div>
      {todo && (
        <>
          <div className="field">
            <label htmlFor={`description-${todo.id}`}>설명</label>
            <textarea id={`description-${todo.id}`} value={description} onChange={(event) => setDescription(event.target.value)} rows={3} />
          </div>
          <div className="field">
            <label htmlFor={`due-${todo.id}`}>기한</label>
            <input id={`due-${todo.id}`} type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
          </div>
        </>
      )}
      <div className="form-actions">
        {onCancel && <button type="button" className="ghost" onClick={onCancel}>취소</button>}
        <button className="primary" disabled={saving}>{saving ? "저장 중…" : todo ? "저장" : "추가"}</button>
      </div>
    </form>
  );
}

function TodoRow({
  todo,
  busy,
  onToggle,
  onSave,
  onDelete
}: {
  todo: Todo;
  busy: boolean;
  onToggle: () => void;
  onSave: (input: TodoInput) => Promise<void>;
  onDelete: () => void;
}) {
  const [editing, setEditing] = useState(false);
  if (editing) {
    return (
      <li className="todo-card editing">
        <TodoEditor todo={todo} onCancel={() => setEditing(false)} onSave={async (input) => {
          await onSave(input);
          setEditing(false);
        }} />
      </li>
    );
  }
  return (
    <li className={`todo-card ${todo.status === "DONE" ? "done" : ""}`}>
      <button
        className="check"
        aria-label={todo.status === "DONE" ? `${todo.title} 미완료로 변경` : `${todo.title} 완료로 변경`}
        aria-pressed={todo.status === "DONE"}
        disabled={busy}
        onClick={onToggle}
      >{todo.status === "DONE" ? "✓" : ""}</button>
      <div className="todo-copy">
        <strong>{todo.title}</strong>
        {todo.description && <p>{todo.description}</p>}
        {todo.dueDate && <small>기한 {todo.dueDate}</small>}
      </div>
      <div className="row-actions">
        <button className="icon-button" disabled={busy} onClick={() => setEditing(true)} aria-label={`${todo.title} 수정`}>수정</button>
        <button className="icon-button danger" disabled={busy} onClick={onDelete} aria-label={`${todo.title} 삭제`}>삭제</button>
      </div>
    </li>
  );
}

export function App() {
  const initial = readFilters();
  const [session, setSession] = useState<Session | null>(null);
  const [sessionError, setSessionError] = useState("");
  const [todos, setTodos] = useState<Todo[]>([]);
  const [status, setStatus] = useState<Filter>(initial.status);
  const [query, setQuery] = useState(initial.query);
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState("");
  const [notice, setNotice] = useState("");
  const [busyIds, setBusyIds] = useState<Set<string>>(new Set());
  const [dark, setDark] = useState(() => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false);
  const requestNumber = useRef(0);

  useEffect(() => {
    api.session().then(setSession).catch((error) => {
      setSessionError(userMessage(error));
      setSession({ authenticated: false, user: null });
    });
  }, []);

  const loadTodos = useCallback(async (signal?: AbortSignal) => {
    const current = ++requestNumber.current;
    setLoading(true);
    setListError("");
    try {
      const page = await api.todos({ status, query: query.trim() }, signal);
      if (current === requestNumber.current) setTodos(page.items);
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError") && current === requestNumber.current) {
        setListError(userMessage(error));
      }
    } finally {
      if (current === requestNumber.current) setLoading(false);
    }
  }, [query, status]);

  useEffect(() => {
    if (!session?.authenticated) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => void loadTodos(controller.signal), 250);
    const params = new URLSearchParams();
    if (status !== "ALL") params.set("status", status);
    if (query.trim()) params.set("q", query.trim());
    window.history.replaceState(null, "", `${window.location.pathname}${params.size ? `?${params}` : ""}`);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [loadTodos, query, session?.authenticated, status]);

  function markBusy(id: string, busy: boolean) {
    setBusyIds((current) => {
      const next = new Set(current);
      busy ? next.add(id) : next.delete(id);
      return next;
    });
  }

  async function createTodo(input: TodoInput) {
    try {
      const created = await api.create(input);
      setTodos((current) => [created, ...current]);
      setNotice("할 일을 추가했습니다.");
    } catch (error) {
      setNotice(userMessage(error));
      throw error;
    }
  }

  async function updateTodo(todo: Todo, input: Partial<TodoInput & { status: Todo["status"] }>) {
    markBusy(todo.id, true);
    try {
      const updated = await api.update(todo, input);
      setTodos((current) => current.map((item) => item.id === todo.id ? updated : item));
      setNotice("변경사항을 저장했습니다.");
    } catch (error) {
      setNotice(userMessage(error));
      if ((error as { status?: number }).status === 409 || (error as { status?: number }).status === 412) void loadTodos();
      throw error;
    } finally {
      markBusy(todo.id, false);
    }
  }

  async function removeTodo(todo: Todo) {
    if (!window.confirm(`“${todo.title}”을(를) 삭제할까요? 이 작업은 되돌릴 수 없습니다.`)) return;
    markBusy(todo.id, true);
    try {
      await api.remove(todo);
      setTodos((current) => current.filter((item) => item.id !== todo.id));
      setNotice("할 일을 삭제했습니다.");
    } catch (error) {
      setNotice(userMessage(error));
    } finally {
      markBusy(todo.id, false);
    }
  }

  async function logout() {
    try {
      await api.logout();
    } finally {
      setSession({ authenticated: false, user: null });
    }
  }

  if (!session) return <main className="center" aria-busy="true"><div className="spinner" /><p>세션을 확인하고 있습니다.</p></main>;
  if (!session.authenticated) return <LoginPage error={sessionError} />;

  return (
    <div className={dark ? "app dark" : "app"}>
      <header className="topbar">
        <a className="logo" href="/todos" aria-label="Flow Todo 홈"><span>✓</span> Flow Todo</a>
        <div className="account">
          <button className="icon-button" onClick={() => setDark((value) => !value)} aria-label={dark ? "라이트 모드 사용" : "다크 모드 사용"}>{dark ? "☀" : "☾"}</button>
          <span className="avatar" aria-hidden="true">{session.user?.displayName?.slice(0, 1) || "U"}</span>
          <span className="user-name">{session.user?.displayName}</span>
          <button className="ghost" onClick={() => void logout()}>로그아웃</button>
        </div>
      </header>
      <main className="content">
        <section className="hero">
          <p className="eyebrow">MY TASKS</p>
          <h1>할 일</h1>
          <p className="muted">작은 완료를 쌓아 오늘의 흐름을 만드세요.</p>
        </section>

        <TodoEditor onSave={createTodo} />

        <section className="toolbar" aria-label="할 일 필터">
          <div className="search">
            <label className="sr-only" htmlFor="search">제목 검색</label>
            <span aria-hidden="true">⌕</span>
            <input id="search" type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="할 일 검색" maxLength={100} />
          </div>
          <div className="segments">
            {(["ALL", "OPEN", "DONE"] as const).map((value) => (
              <button key={value} className={status === value ? "active" : ""} aria-pressed={status === value} onClick={() => setStatus(value)}>
                {value === "ALL" ? "전체" : value === "OPEN" ? "진행" : "완료"}
              </button>
            ))}
          </div>
        </section>

        <div className="list-heading">
          <h2>{status === "ALL" ? "모든 할 일" : status === "OPEN" ? "진행 중" : "완료됨"}</h2>
          {!loading && <span>{todos.length}개</span>}
        </div>

        {listError && <div className="alert error" role="alert"><p>{listError}</p><button className="ghost" onClick={() => void loadTodos()}>다시 시도</button></div>}
        {loading && todos.length === 0 && <div className="skeletons" aria-label="할 일 불러오는 중"><div /><div /><div /></div>}
        {!loading && !listError && todos.length === 0 && (
          <section className="empty">
            <span aria-hidden="true">✓</span>
            <h2>{query || status !== "ALL" ? "조건에 맞는 할 일이 없습니다" : "아직 할 일이 없습니다"}</h2>
            <p>{query || status !== "ALL" ? "검색어나 필터를 바꿔 보세요." : "위 입력창에서 첫 할 일을 추가해 보세요."}</p>
          </section>
        )}
        <ul className="todo-list" aria-busy={loading}>
          {todos.map((todo) => (
            <TodoRow
              key={todo.id}
              todo={todo}
              busy={busyIds.has(todo.id)}
              onToggle={() => void updateTodo(todo, { status: todo.status === "DONE" ? "OPEN" : "DONE" }).catch(() => undefined)}
              onSave={(input) => updateTodo(todo, input)}
              onDelete={() => void removeTodo(todo)}
            />
          ))}
        </ul>
      </main>
      <div className="sr-only" role="status" aria-live="polite">{notice}</div>
    </div>
  );
}

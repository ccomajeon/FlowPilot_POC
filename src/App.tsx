import { FormEvent, MouseEvent, useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, DueFilter, onAuthExpired, Session, Todo, TodoInput, TodoSort, userMessage } from "./api";

type Filter = "ALL" | "OPEN" | "DONE";
type QueryFilters = { status: Filter; query: string; due: DueFilter; sort: TodoSort };

function readFilters(): QueryFilters {
  const params = new URLSearchParams(window.location.search);
  const statusValue = params.get("status");
  const dueValue = params.get("due");
  const sortValue = params.get("sort");
  return {
    status: statusValue === "OPEN" || statusValue === "DONE" ? statusValue : "ALL",
    query: params.get("q")?.trim().slice(0, 100) ?? "",
    due: dueValue === "TODAY" || dueValue === "OVERDUE" || dueValue === "UPCOMING" ? dueValue : "ALL",
    sort: sortValue === "createdAt:desc" ? sortValue : "updatedAt:desc"
  };
}

function localCalendarDate(date = new Date()): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function matchesDueFilter(todo: Todo, due: DueFilter): boolean {
  if (due === "ALL") return true;
  if (!todo.dueDate) return false;
  const today = localCalendarDate();
  if (due === "TODAY") return todo.dueDate === today;
  if (due === "OVERDUE") return todo.dueDate < today;
  return todo.dueDate > today;
}

const DISCARD_MESSAGE = "저장하지 않은 변경사항이 있습니다. 변경사항을 폐기할까요?";

function LoginPage({ error, onRetryLogout }: { error?: string; onRetryLogout?: () => void }) {
  return (
    <main className="login-shell">
      <section className="login-card" aria-labelledby="login-title">
        <div className="brand-mark" aria-hidden="true">✓</div>
        <p className="eyebrow">FLOW TODO</p>
        <h1 id="login-title">오늘 할 일을<br />가볍게 정리하세요.</h1>
        <p className="muted">안전한 OAuth 로그인으로 어디서든 나만의 할 일에 접근할 수 있습니다.</p>
        {error && <p className="alert error" role="alert">{error}</p>}
        <button className="primary wide" onClick={() => api.login()}>OAuth로 계속하기</button>
        {onRetryLogout && <button className="ghost wide" onClick={onRetryLogout}>서버 로그아웃 다시 시도</button>}
        <p className="legal">계속하면 서비스 이용약관과 개인정보처리방침에 동의하게 됩니다.</p>
      </section>
    </main>
  );
}

function TodoEditor({
  todo,
  onSave,
  onCancel,
  onDirtyChange
}: {
  todo?: Todo;
  onSave: (input: TodoInput) => Promise<void>;
  onCancel?: () => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [title, setTitle] = useState(todo?.title ?? "");
  const [description, setDescription] = useState(todo?.description ?? "");
  const [dueDate, setDueDate] = useState(todo?.dueDate ?? "");
  const [saving, setSaving] = useState(false);
  const [fieldError, setFieldError] = useState("");

  const dirty = Boolean(todo) && (
    title !== (todo?.title ?? "")
    || description !== (todo?.description ?? "")
    || dueDate !== (todo?.dueDate ?? "")
  );

  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);

  function cancel() {
    if (!dirty || window.confirm(DISCARD_MESSAGE)) onCancel?.();
  }

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
        {onCancel && <button type="button" className="ghost" onClick={cancel}>취소</button>}
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
  onDelete,
  onDirtyChange
}: {
  todo: Todo;
  busy: boolean;
  onToggle: () => void;
  onSave: (input: TodoInput) => Promise<void>;
  onDelete: () => void;
  onDirtyChange: (id: string, dirty: boolean) => void;
}) {
  const [editing, setEditing] = useState(false);
  const editButton = useRef<HTMLButtonElement>(null);
  const reportDirty = useCallback(
    (dirty: boolean) => onDirtyChange(todo.id, dirty),
    [onDirtyChange, todo.id]
  );

  const closeEditor = useCallback(() => {
    setEditing(false);
    onDirtyChange(todo.id, false);
    window.setTimeout(() => editButton.current?.focus(), 0);
  }, [onDirtyChange, todo.id]);

  if (editing) {
    return (
      <li className="todo-card editing">
        <TodoEditor todo={todo} onCancel={closeEditor} onDirtyChange={reportDirty} onSave={async (input) => {
          await onSave(input);
          closeEditor();
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
        <button ref={editButton} className="icon-button" disabled={busy} onClick={() => setEditing(true)} aria-label={`${todo.title} 수정`}>수정</button>
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
  const [due, setDue] = useState<DueFilter>(initial.due);
  const [sort, setSort] = useState<TodoSort>(initial.sort);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string>();
  const [listError, setListError] = useState("");
  const [notice, setNotice] = useState("");
  const [busyIds, setBusyIds] = useState<Set<string>>(new Set());
  const [dirtyEditorIds, setDirtyEditorIds] = useState<Set<string>>(new Set());
  const [serverLogoutPending, setServerLogoutPending] = useState(false);
  const [dark, setDark] = useState(() => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false);
  const requestNumber = useRef(0);
  const seenCursors = useRef(new Set<string>());
  const pageCount = useRef(0);
  const itemCount = useRef(0);

  const markEditorDirty = useCallback((id: string, dirty: boolean) => {
    setDirtyEditorIds((current) => {
      if (current.has(id) === dirty) return current;
      const next = new Set(current);
      dirty ? next.add(id) : next.delete(id);
      return next;
    });
  }, []);

  useEffect(() => {
    if (dirtyEditorIds.size === 0) return;
    const protectUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", protectUnload);
    return () => window.removeEventListener("beforeunload", protectUnload);
  }, [dirtyEditorIds.size]);

  useEffect(() => onAuthExpired(() => {
    requestNumber.current += 1;
    setTodos([]);
    setBusyIds(new Set());
    setSessionError("로그인이 만료되었습니다. 다시 로그인해 주세요.");
    setSession({ authenticated: false, user: null });
  }), []);

  useEffect(() => {
    api.session().then(setSession).catch((error) => {
      setSessionError(userMessage(error));
      setSession({ authenticated: false, user: null });
    });
  }, []);

  const loadTodos = useCallback(async (signal?: AbortSignal, cursor?: string, append = false) => {
    const current = ++requestNumber.current;
    append ? setLoadingMore(true) : setLoading(true);
    setListError("");
    try {
      const page = await api.todos(
        { status, query: query.trim(), due, sort },
        signal,
        cursor,
        ({ attempt }) => setNotice(`목록 조회를 다시 시도하고 있습니다. ${attempt}/2`)
      );
      if (current !== requestNumber.current) return;
      if (!append) {
        seenCursors.current.clear();
        pageCount.current = 0;
        itemCount.current = 0;
      }
      if (cursor) seenCursors.current.add(cursor);
      const followingCursor = page.nextCursor ?? undefined;
      if (followingCursor && seenCursors.current.has(followingCursor)) {
        throw new ApiError(0, "페이지 응답이 올바르지 않습니다.", "INVALID_CURSOR");
      }
      const remaining = Math.max(0, 500 - itemCount.current);
      const incoming = page.items.slice(0, remaining);
      itemCount.current += incoming.length;
      pageCount.current += 1;
      setTodos((currentTodos) => {
        const merged = new Map((append ? currentTodos : []).map((todo) => [todo.id, todo]));
        incoming.forEach((todo) => merged.set(todo.id, todo));
        return [...merged.values()];
      });
      const limited = pageCount.current >= 20 || itemCount.current >= 500;
      setNextCursor(limited ? undefined : followingCursor);
      if (limited && followingCursor) setListError("표시 가능한 목록 한도에 도달했습니다. 필터를 좁혀 주세요.");
      else setNotice("");
    } catch (error) {
      if (!(error instanceof DOMException && error.name === "AbortError") && current === requestNumber.current) {
        setListError(userMessage(error));
      }
    } finally {
      if (current === requestNumber.current) {
        append ? setLoadingMore(false) : setLoading(false);
      }
    }
  }, [due, query, sort, status]);

  useEffect(() => {
    if (!session?.authenticated) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => void loadTodos(controller.signal), 250);
    const params = new URLSearchParams();
    if (status !== "ALL") params.set("status", status);
    if (query.trim()) params.set("q", query.trim());
    if (due !== "ALL") params.set("due", due);
    if (sort !== "updatedAt:desc") params.set("sort", sort);
    window.history.replaceState(null, "", `${window.location.pathname}${params.size ? `?${params}` : ""}`);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [due, loadTodos, query, session?.authenticated, sort, status]);

  function markBusy(id: string, busy: boolean) {
    setBusyIds((current) => {
      const next = new Set(current);
      busy ? next.add(id) : next.delete(id);
      return next;
    });
  }

  function matchesFilters(todo: Todo): boolean {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    const baseMatch = (status === "ALL" || todo.status === status)
      && (!normalizedQuery || todo.title.toLocaleLowerCase().includes(normalizedQuery));
    return baseMatch && matchesDueFilter(todo, due);
  }

  async function createTodo(input: TodoInput) {
    try {
      const created = await api.create(input);
      if (matchesFilters(created)) setTodos((current) => [created, ...current.filter((item) => item.id !== created.id)]);
      setNotice(matchesFilters(created) ? "할 일을 추가했습니다." : "할 일을 추가했지만 현재 필터에는 표시되지 않습니다.");
    } catch (error) {
      setNotice(userMessage(error));
      throw error;
    }
  }

  async function updateTodo(todo: Todo, input: Partial<TodoInput & { status: Todo["status"] }>) {
    markBusy(todo.id, true);
    try {
      const updated = await api.update(todo, input);
      setTodos((current) => matchesFilters(updated)
        ? current.map((item) => item.id === todo.id ? updated : item)
        : current.filter((item) => item.id !== todo.id));
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
    if (dirtyEditorIds.size > 0 && !window.confirm(DISCARD_MESSAGE)) return;
    setTodos([]);
    setBusyIds(new Set());
    setDirtyEditorIds(new Set());
    setSessionError("");
    setServerLogoutPending(false);
    setSession({ authenticated: false, user: null });
    try {
      await api.logout();
    } catch (error) {
      setSessionError("로컬 데이터는 제거했지만 서버 세션을 종료하지 못했습니다. " + userMessage(error));
      setServerLogoutPending(true);
    }
  }

  async function retryLogout() {
    try {
      await api.retryLogout();
      setServerLogoutPending(false);
      setSessionError("");
    } catch (error) {
      setSessionError("서버 세션을 종료하지 못했습니다. " + userMessage(error));
    }
  }

  function protectNavigation(event: MouseEvent<HTMLAnchorElement>) {
    if (dirtyEditorIds.size > 0 && !window.confirm(DISCARD_MESSAGE)) {
      event.preventDefault();
    }
  }

  if (!session) return <main className="center" aria-busy="true"><div className="spinner" /><p>세션을 확인하고 있습니다.</p></main>;
  if (!session.authenticated) return <LoginPage error={sessionError} onRetryLogout={serverLogoutPending ? () => void retryLogout() : undefined} />;

  return (
    <div className={dark ? "app dark" : "app"}>
      <header className="topbar">
        <a className="logo" href="/todos" aria-label="Flow Todo 홈" onClick={protectNavigation}><span>✓</span> Flow Todo</a>
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
          <div className="filter-selects">
            <label>
              <span>기한</span>
              <select aria-label="기한 필터" value={due} onChange={(event) => setDue(event.target.value as DueFilter)}>
                <option value="ALL">모든 기한</option>
                <option value="TODAY">오늘</option>
                <option value="OVERDUE">기한 지남</option>
                <option value="UPCOMING">예정</option>
              </select>
            </label>
            <label>
              <span>정렬</span>
              <select aria-label="정렬" value={sort} onChange={(event) => setSort(event.target.value as TodoSort)}>
                <option value="updatedAt:desc">최근 수정순</option>
                <option value="createdAt:desc">최근 생성순</option>
              </select>
            </label>
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
            <h2>{query || status !== "ALL" || due !== "ALL" ? "조건에 맞는 할 일이 없습니다" : "아직 할 일이 없습니다"}</h2>
            <p>{query || status !== "ALL" || due !== "ALL" ? "검색어나 필터를 바꿔 보세요." : "위 입력창에서 첫 할 일을 추가해 보세요."}</p>
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
              onDirtyChange={markEditorDirty}
            />
          ))}
        </ul>
        {nextCursor && !listError && (
          <button className="ghost load-more" disabled={loadingMore} onClick={() => void loadTodos(undefined, nextCursor, true)}>{loadingMore ? "불러오는 중…" : "더 보기"}</button>
        )}
      </main>
      <div className="sr-only" role="status" aria-live="polite">{notice}</div>
    </div>
  );
}

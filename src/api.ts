export type User = { id: string; displayName: string };
export type Session = { authenticated: boolean; user: User | null; csrfToken?: string };
export type Todo = {
  id: string;
  title: string;
  description: string | null;
  status: "OPEN" | "DONE";
  dueDate: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};
export type TodoInput = { title: string; description?: string | null; dueDate?: string | null };
export type TodoPage = { items: Todo[]; nextCursor?: string | null };

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code = "REQUEST_FAILED",
    public readonly traceId?: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

let csrfToken: string | undefined;
let sessionRefresh: Promise<boolean> | null = null;
let authenticationExpired = false;
const authExpiredListeners = new Set<() => void>();

export function onAuthExpired(listener: () => void): () => void {
  authExpiredListeners.add(listener);
  return () => {
    authExpiredListeners.delete(listener);
  };
}

function expireAuthentication(): void {
  csrfToken = undefined;
  if (authenticationExpired) return;
  authenticationExpired = true;
  authExpiredListeners.forEach((listener) => listener());
}

function contractError(): ApiError {
  return new ApiError(0, "서버 응답 형식이 올바르지 않습니다.", "INVALID_RESPONSE");
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw contractError();
  return value as Record<string, unknown>;
}

function string(value: unknown): string {
  if (typeof value !== "string" || !value) throw contractError();
  return value;
}

function nullableString(value: unknown): string | null {
  return value === null ? null : string(value);
}

function parseSession(value: unknown): Session {
  const data = record(value);
  if (typeof data.authenticated !== "boolean") throw contractError();
  const rawUser = data.user === null ? null : record(data.user);
  const user = rawUser ? { id: string(rawUser.id), displayName: string(rawUser.displayName) } : null;
  if (data.authenticated !== Boolean(user)) throw contractError();
  if (data.csrfToken !== undefined && (typeof data.csrfToken !== "string" || !data.csrfToken)) throw contractError();
  return { authenticated: data.authenticated, user, csrfToken: data.csrfToken as string | undefined };
}

function parseTodo(value: unknown): Todo {
  const data = record(value);
  const status = data.status;
  const dueDate = nullableString(data.dueDate);
  const createdAt = string(data.createdAt);
  const updatedAt = string(data.updatedAt);
  if (status !== "OPEN" && status !== "DONE") throw contractError();
  if (!Number.isInteger(data.version) || (data.version as number) < 0) throw contractError();
  if (dueDate !== null && !/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) throw contractError();
  if (Number.isNaN(Date.parse(createdAt)) || Number.isNaN(Date.parse(updatedAt))) throw contractError();
  return {
    id: string(data.id), title: string(data.title), description: nullableString(data.description),
    status, dueDate, version: data.version as number, createdAt, updatedAt
  };
}

function parseTodoPage(value: unknown): TodoPage {
  const data = record(value);
  if (!Array.isArray(data.items)) throw contractError();
  if (data.nextCursor !== undefined && data.nextCursor !== null && typeof data.nextCursor !== "string") throw contractError();
  return { items: data.items.map(parseTodo), nextCursor: data.nextCursor as string | null | undefined };
}

async function parseError(response: Response): Promise<ApiError> {
  let body: { code?: string; traceId?: string } = {};
  try {
    body = (await response.json()) as typeof body;
  } catch {
    // Error bodies are optional and never echoed to the UI.
  }
  const messages: Record<number, string> = {
    401: "로그인이 만료되었습니다. 다시 로그인해 주세요.",
    403: "요청한 작업을 수행할 권한이 없습니다.",
    404: "요청한 항목을 찾을 수 없습니다.",
    409: "다른 곳에서 변경되었습니다. 목록을 새로 확인해 주세요.",
    412: "다른 곳에서 변경되었습니다. 목록을 새로 확인해 주세요.",
    422: "입력한 내용을 확인해 주세요.",
    429: "요청이 많습니다. 잠시 후 다시 시도해 주세요."
  };
  const fallback = response.status >= 500
    ? "서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요."
    : "요청을 처리하지 못했습니다.";
  return new ApiError(response.status, messages[response.status] ?? fallback, body.code, body.traceId);
}

async function refreshSession(): Promise<boolean> {
  if (!sessionRefresh) {
    sessionRefresh = fetch("/auth/session", {
      credentials: "include",
      headers: { Accept: "application/json" }
    })
      .then(async (response) => {
        if (!response.ok) return false;
        const session = parseSession(await response.json());
        csrfToken = session.authenticated ? session.csrfToken : undefined;
        authenticationExpired = !session.authenticated;
        return session.authenticated;
      })
      .catch(() => false)
      .then((authenticated) => {
        if (!authenticated) expireAuthentication();
        return authenticated;
      })
      .finally(() => {
        sessionRefresh = null;
      });
  }
  return sessionRefresh;
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  retryAuth = true,
  validate?: (value: unknown) => T
): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    if (!csrfToken) {
      throw new ApiError(0, "보안 토큰이 없어 요청을 전송하지 않았습니다. 다시 로그인해 주세요.", "CSRF_TOKEN_MISSING");
    }
    headers.set("X-CSRF-Token", csrfToken);
  }

  const response = await fetch(path, { ...init, method, headers, credentials: "include" });
  if (response.status === 401) {
    if (retryAuth && (await refreshSession())) return request<T>(path, init, false, validate);
    if (!retryAuth) expireAuthentication();
  }
  if (!response.ok) throw await parseError(response);
  if (response.status === 204) return undefined as T;
  let value: unknown;
  try {
    value = await response.json();
  } catch {
    throw contractError();
  }
  return validate ? validate(value) : value as T;
}

export const api = {
  async session(): Promise<Session> {
    const response = await fetch("/auth/session", {
      credentials: "include",
      headers: { Accept: "application/json" }
    });
    if (response.status === 401) {
      csrfToken = undefined;
      authenticationExpired = true;
      return { authenticated: false, user: null };
    }
    if (!response.ok) throw await parseError(response);
    let session: Session;
    try {
      session = parseSession(await response.json());
    } catch {
      throw contractError();
    }
    csrfToken = session.authenticated ? session.csrfToken : undefined;
    authenticationExpired = !session.authenticated;
    return session;
  },

  login(): void {
    window.location.assign("/auth/login?returnTo=%2Ftodos");
  },

  async logout(): Promise<void> {
    try {
      await request<void>("/auth/logout", { method: "POST" }, false);
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) throw error;
    } finally {
      csrfToken = undefined;
    }
  },

  async retryLogout(): Promise<void> {
    const session = await api.session();
    if (session.authenticated) await api.logout();
  },

  todos(filters: { status: string; query: string }, signal?: AbortSignal, cursor?: string): Promise<TodoPage> {
    const params = new URLSearchParams({ sort: "updatedAt:desc" });
    if (filters.status !== "ALL") params.set("status", filters.status);
    if (filters.query) params.set("q", filters.query);
    if (cursor) params.set("cursor", cursor);
    return request<TodoPage>(`/api/v1/todos?${params}`, { signal }, true, parseTodoPage);
  },

  create(input: TodoInput): Promise<Todo> {
    return request<Todo>("/api/v1/todos", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(input)
    }, true, parseTodo);
  },

  update(todo: Todo, input: Partial<TodoInput & { status: Todo["status"] }>): Promise<Todo> {
    return request<Todo>(`/api/v1/todos/${encodeURIComponent(todo.id)}`, {
      method: "PATCH",
      headers: { "If-Match": String(todo.version) },
      body: JSON.stringify(input)
    }, true, parseTodo);
  },

  remove(todo: Todo): Promise<void> {
    return request<void>(`/api/v1/todos/${encodeURIComponent(todo.id)}`, {
      method: "DELETE",
      headers: { "If-Match": String(todo.version) }
    });
  }
};

export function userMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.traceId ? `${error.message} (추적 ID: ${error.traceId})` : error.message;
  }
  return "네트워크 연결을 확인하고 다시 시도해 주세요.";
}

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
        const session = (await response.json()) as Session;
        csrfToken = session.csrfToken;
        return session.authenticated;
      })
      .catch(() => false)
      .finally(() => {
        sessionRefresh = null;
      });
  }
  return sessionRefresh;
}

async function request<T>(path: string, init: RequestInit = {}, retryAuth = true): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method) && csrfToken) headers.set("X-CSRF-Token", csrfToken);

  const response = await fetch(path, { ...init, method, headers, credentials: "include" });
  if (response.status === 401 && retryAuth && (await refreshSession())) {
    return request<T>(path, init, false);
  }
  if (!response.ok) throw await parseError(response);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  async session(): Promise<Session> {
    const response = await fetch("/auth/session", {
      credentials: "include",
      headers: { Accept: "application/json" }
    });
    if (response.status === 401) return { authenticated: false, user: null };
    if (!response.ok) throw await parseError(response);
    const session = (await response.json()) as Session;
    csrfToken = session.csrfToken;
    return session;
  },

  login(): void {
    window.location.assign("/auth/login?returnTo=%2Ftodos");
  },

  async logout(): Promise<void> {
    await request<void>("/auth/logout", { method: "POST" }, false);
    csrfToken = undefined;
  },

  todos(filters: { status: string; query: string }, signal?: AbortSignal): Promise<TodoPage> {
    const params = new URLSearchParams({ sort: "updatedAt:desc" });
    if (filters.status !== "ALL") params.set("status", filters.status);
    if (filters.query) params.set("q", filters.query);
    return request<TodoPage>(`/api/v1/todos?${params}`, { signal });
  },

  create(input: TodoInput): Promise<Todo> {
    return request<Todo>("/api/v1/todos", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify(input)
    });
  },

  update(todo: Todo, input: Partial<TodoInput & { status: Todo["status"] }>): Promise<Todo> {
    return request<Todo>(`/api/v1/todos/${encodeURIComponent(todo.id)}`, {
      method: "PATCH",
      headers: { "If-Match": String(todo.version) },
      body: JSON.stringify(input)
    });
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

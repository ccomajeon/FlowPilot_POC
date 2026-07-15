# Flow Todo

OAuth 인증 사용자가 개인 할 일을 관리하는 React SPA입니다.

## 요구사항

- Node.js 22 LTS
- 동일 출처 BFF 인증 API와 Todo REST API

## 실행 및 검증

```bash
npm install
npm run dev
npm run check
```

브라우저에는 OAuth 토큰을 저장하지 않으며 `/auth/session`, `/auth/login`, `/auth/logout`을 제공하는 BFF를 전제로 합니다. Todo 계약은 `src/api.ts`에 격리되어 있습니다. 실제 OpenAPI, OAuth 공급자, CSRF 정책, 배포 환경은 연동 전에 승인해야 합니다.

## 운영

빌드 산출물은 `dist/`이며 이전 정상 산출물을 보존해 롤백합니다. 정적 호스팅은 SPA fallback, 보안 헤더(CSP, frame-ancestors, nosniff), `index.html`의 짧은 캐시를 설정해야 합니다. 로그에 토큰, 쿠키, OAuth code, 검색어, 할 일 본문을 남기지 않습니다.

## 인증·CSRF 계약

쿠키 인증의 `POST`, `PATCH`, `DELETE` 요청은 `/auth/session`이 발급한 `csrfToken`을 `X-CSRF-Token`으로 전송합니다. 토큰이 없으면 클라이언트가 네트워크 호출 전에 차단합니다. 만료·불일치 토큰은 BFF가 상태 변경 없이 거부해야 합니다.

BFF는 신뢰한 `Origin`만 허용하고 세션 재확인 시 새 CSRF 토큰을 발급해야 합니다. 동시 `401` 재확인은 한 번으로 단일화하며 실패하면 인증 상태와 보호 데이터를 제거합니다.

API 응답은 `src/api.ts`의 런타임 경계 검증을 통과해야 합니다. 실제 OpenAPI 기반 생성 전환, 승인 레지스트리 기반 잠금 파일, CI E2E·접근성·취약점·비밀값 검사는 관련 환경과 계약 확인이 필요합니다.

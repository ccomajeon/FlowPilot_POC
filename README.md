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

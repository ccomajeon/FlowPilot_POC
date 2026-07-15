# FlowPilot_POC

개인 할 일 관리 REST API입니다. Java 21, Spring Boot, PostgreSQL을 사용합니다.

## PostgreSQL 준비

운영 표준 PostgreSQL 버전은 확인 필요입니다. 로컬·CI 테스트는 현재 PostgreSQL 16 컨테이너를 사용합니다. 계정 비밀번호는 명령 프롬프트에서 입력하고 저장소나 셸 이력에 기록하지 않습니다.

```bash
createuser --pwprompt todo_migrator
createdb --owner=todo_migrator todos
createuser --pwprompt todo_app
psql --dbname=todos --username=todo_migrator
```

접속한 `psql`에서 애플리케이션 계정의 접속 권한만 먼저 설정합니다. 모든 테이블 또는 향후 생성될 테이블에 대한 기본 DML 권한은 부여하지 않습니다.

```sql
GRANT CONNECT ON DATABASE todos TO todo_app;
GRANT USAGE ON SCHEMA public TO todo_app;
REVOKE CREATE ON SCHEMA public FROM todo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE todo_migrator IN SCHEMA public
  REVOKE ALL PRIVILEGES ON TABLES FROM todo_app;
```

Flyway를 `todo_migrator`로 적용한 다음, 런타임에 필요한 테이블만 명시적으로 허용합니다.

```sql
REVOKE ALL PRIVILEGES ON TABLE public.flyway_schema_history FROM todo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.todos TO todo_app;
```

배포 전에 실제 역할을 대상으로 권한을 확인합니다. 첫 번째 조회는 모두 `t`, 두 번째 조회는 모두 `f`여야 합니다.

```sql
SELECT has_table_privilege('todo_app', 'public.todos', privilege)
FROM unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE']) AS privilege;

SELECT has_table_privilege('todo_app', 'public.flyway_schema_history', privilege)
FROM unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE']) AS privilege;
```

`todo_app`에는 `CREATE`, `ALTER`, `DROP` 또는 Flyway schema history 조회·변경 권한을 부여하지 않습니다. 운영에서는 계정 생성과 GRANT를 플랫폼 관리 절차로 실행하고 실제 권한 거부를 배포 전에 확인해야 합니다.

## 실행

다음 환경변수를 Secret Manager 또는 로컬 프로세스 환경에서 설정합니다.

```text
DB_URL=jdbc:postgresql://localhost:5432/todos
DB_USERNAME=todo_app
DB_PASSWORD=<local-secret>
FLYWAY_DB_URL=jdbc:postgresql://localhost:5432/todos
FLYWAY_DB_USERNAME=todo_migrator
FLYWAY_DB_PASSWORD=<different-local-secret>
JWT_ISSUER_URI=https://approved-idp.example/issuer
JWT_JWK_SET_URI=https://approved-idp.example/issuer/jwks
JWT_AUDIENCE=todo-api
TODO_CORS_ALLOWED_ORIGINS=https://approved-ui.example
```

```bash
mvn spring-boot:run
```

운영 IdP의 issuer, audience, `sub` 불변성·비재사용성과 CORS origin은 배포 전에 승인해야 합니다. `TODO_CORS_ALLOWED_ORIGINS`는 쉼표로 구분하며 미설정 시 브라우저 교차 출처 요청을 허용하지 않습니다.

## 테스트

Java 21과 실행 가능한 Docker가 필수입니다.

```bash
mvn test -Dtest.postgres.image="postgres:16.9-alpine@sha256:<승인된-digest>"
```

`test.postgres.image`는 필수이며 `postgres:16.9-alpine@sha256:<64자리 승인 digest>` 형식만 허용됩니다. 속성을 생략하거나 부동 태그, 다른 PostgreSQL 버전 또는 digest 없는 이미지를 지정하면 테스트는 컨테이너 시작 전에 실패합니다. 운영 PostgreSQL 버전이 확정되면 허용 버전과 테스트 이미지를 같은 메이저·마이너 버전으로 함께 변경하고 검증합니다.

Testcontainers가 PostgreSQL을 시작하지 못하면 통합 테스트를 건너뛰지 않고 빌드를 실패시킵니다. CI는 동일 Git 커밋에서 최소한 다음을 보존해야 합니다.

- 실행 명령, Java·Maven·Docker·PostgreSQL 이미지 버전
- Testcontainers가 실제로 해석한 PostgreSQL 이미지 digest
- `target/surefire-reports/*.xml`
- Maven 및 Testcontainers 로그
- 대상 Git 커밋 SHA
- Maven 의존성 및 배포 이미지 SCA/CVE 보고서와 승인된 예외
- 분리된 migrator/runtime DB 계정의 권한 검증 결과

테스트는 Flyway, PostgreSQL 실제 타입과 제약조건, CRUD, 소유권 격리, 필터·페이지 제한, 표준 오류, 트랜잭션 롤백과 실제 병렬 낙관적 잠금을 검증합니다. 실제 JWT 서명 검증은 승인된 IdP와 동일한 키 회전·알고리즘 정책을 사용하는 별도 보안 통합 환경에서도 수행해야 합니다.

Actuator의 `http.server.requests`에는 정규화된 URI와 상태 코드별 요청 수·지연시간이 기록되며 p95/p99 histogram이 활성화됩니다. 배포 환경에서는 스크레이프 성공 증빙과 승인된 SLO에 따른 지연시간·5xx·DB pool·readiness 알림 규칙을 함께 제출해야 합니다.

## 운영 및 복구

The runtime account must only have `SELECT`, `INSERT`, `UPDATE`, and `DELETE` privileges on `todos`. Use the separate Flyway account for DDL. Todo endpoints require the `todos` scope; protected Actuator endpoints require `todos.admin`.

Readiness includes the database health indicator. DB 연결을 차단한 상태에서 `/actuator/health/readiness`가 실패하고 트래픽에서 제거되는지 배포 환경별로 검증해야 합니다.

배포 전 다음 복원·롤백 리허설 결과를 변경 기록에 첨부합니다.

1. 운영과 같은 PostgreSQL 버전의 백업 또는 PITR 복원본을 격리 환경에 생성합니다.
2. Flyway 버전, 제약조건, 인덱스, 행 수와 표본 무결성을 확인합니다.
3. 신규 스키마에서 이전 애플리케이션 이미지를 기동해 읽기·쓰기 회귀 테스트를 수행합니다.
4. readiness, 오류율, DB pool 및 핵심 API를 확인합니다.
5. 측정된 복원 시간과 데이터 손실 범위를 승인된 RTO/RPO와 비교합니다.

변경 기록에는 복원 원본과 시점, PostgreSQL 버전, Flyway 버전, 이전·신규 애플리케이션 이미지 digest, 실행 명령, 시작·종료 시각, 측정 RTO/RPO 및 회귀 테스트 결과를 포함합니다. 절차 문서만 있거나 측정 결과가 없으면 배포 승인 근거로 사용하지 않습니다.

적용된 Flyway 파일을 수정하거나 애플리케이션 롤백을 위해 운영 테이블을 삭제하지 않습니다. 호환 가능한 이전 이미지를 배포하고, 데이터 손상은 검증된 백업/PITR 또는 새 forward-fix 마이그레이션으로 복구합니다.

## 운영 주의사항

JWT 발급자와 `sub` 정책, 운영 PostgreSQL 버전, SLO/RPO/RTO, 백업/PITR, 로그 보존기간 및 취약점 검사 기준은 확인 필요입니다. 요청·응답 본문, Authorization, 쿠키, JWT 원문, DB 비밀번호와 전체 접속 문자열을 로그에 기록하지 않습니다. Actuator health만 공개되며 나머지 엔드포인트는 인증이 필요합니다.

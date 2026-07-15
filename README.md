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

접속한 `psql`에서 애플리케이션 계정의 최소 권한과 이후 Flyway 테이블의 기본 권한을 설정합니다.

```sql
GRANT CONNECT ON DATABASE todos TO todo_app;
GRANT USAGE ON SCHEMA public TO todo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO todo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE todo_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO todo_app;
```

`todo_app`에는 `CREATE`, `ALTER`, `DROP` 또는 Flyway schema history 변경 권한을 부여하지 않습니다. 운영에서는 계정 생성과 GRANT를 플랫폼 관리 절차로 실행하고 실제 권한 거부를 배포 전에 확인해야 합니다.

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
mvn test
```

Testcontainers가 PostgreSQL을 시작하지 못하면 통합 테스트를 건너뛰지 않고 빌드를 실패시킵니다. CI는 최소한 다음을 보존해야 합니다.

- 실행 명령, Java·Maven·Docker·PostgreSQL 이미지 버전
- `target/surefire-reports/*.xml`
- Maven 및 Testcontainers 로그
- 대상 Git 커밋 SHA

테스트는 Flyway, PostgreSQL 실제 타입과 제약조건, CRUD, 소유권 격리, 필터·페이지 제한, 표준 오류, 트랜잭션 롤백과 실제 병렬 낙관적 잠금을 검증합니다. 실제 JWT 서명 검증은 승인된 IdP와 동일한 키 회전·알고리즘 정책을 사용하는 별도 보안 통합 환경에서도 수행해야 합니다.

## 운영 및 복구

The runtime account must only have `SELECT`, `INSERT`, `UPDATE`, and `DELETE` privileges on `todos`. Use the separate Flyway account for DDL. Todo endpoints require the `todos` scope; protected Actuator endpoints require `todos.admin`.

Readiness includes the database health indicator. DB 연결을 차단한 상태에서 `/actuator/health/readiness`가 실패하고 트래픽에서 제거되는지 배포 환경별로 검증해야 합니다.

배포 전 다음 복원·롤백 리허설 결과를 변경 기록에 첨부합니다.

1. 운영과 같은 PostgreSQL 버전의 백업 또는 PITR 복원본을 격리 환경에 생성합니다.
2. Flyway 버전, 제약조건, 인덱스, 행 수와 표본 무결성을 확인합니다.
3. 신규 스키마에서 이전 애플리케이션 이미지를 기동해 읽기·쓰기 회귀 테스트를 수행합니다.
4. readiness, 오류율, DB pool 및 핵심 API를 확인합니다.
5. 측정된 복원 시간과 데이터 손실 범위를 승인된 RTO/RPO와 비교합니다.

적용된 Flyway 파일을 수정하거나 애플리케이션 롤백을 위해 운영 테이블을 삭제하지 않습니다. 호환 가능한 이전 이미지를 배포하고, 데이터 손상은 검증된 백업/PITR 또는 새 forward-fix 마이그레이션으로 복구합니다.

## 운영 주의사항

JWT 발급자와 `sub` 정책, 운영 PostgreSQL 버전, SLO/RPO/RTO, 백업/PITR, 로그 보존기간 및 취약점 검사 기준은 확인 필요입니다. 요청·응답 본문, Authorization, 쿠키, JWT 원문, DB 비밀번호와 전체 접속 문자열을 로그에 기록하지 않습니다. Actuator health만 공개되며 나머지 엔드포인트는 인증이 필요합니다.

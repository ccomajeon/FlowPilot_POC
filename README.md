# FlowPilot_POC

개인 할 일 관리 REST API입니다. Java 21, Spring Boot, PostgreSQL을 사용합니다.

## 실행

PostgreSQL에 데이터베이스와 최소 권한 애플리케이션 계정을 준비한 뒤 환경변수를 설정합니다.

```text
DB_URL=jdbc:postgresql://localhost:5432/todos
DB_USERNAME=todo_app
DB_PASSWORD=<local-secret>
JWT_ISSUER_URI=https://approved-idp.example/issuer
```

```bash
mvn spring-boot:run
mvn test
```

테스트는 Docker가 있을 때 Testcontainers PostgreSQL에서 Flyway, 실제 타입, CRUD, 소유권 격리와 낙관적 잠금을 검증합니다. Docker가 없으면 통합 테스트는 건너뜁니다.

## 운영 주의사항

JWT 발급자와 `sub` 불변성, CORS, 운영 PostgreSQL 버전, SLO/RPO/RTO, 백업/PITR 및 복원 훈련은 배포 전에 확정해야 합니다. Flyway 적용 파일은 수정하지 않고 전용 DDL 계정으로 새 마이그레이션을 추가합니다. 애플리케이션 롤백 시 스키마를 역변경하지 말고 호환 가능한 이전 이미지를 배포하며, 데이터 손상은 검증된 백업/PITR로 복구합니다. Actuator health만 공개되며 나머지 엔드포인트는 인증이 필요합니다.

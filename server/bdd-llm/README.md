# BDD Gap Analyzer

운영 오류를 제품 명세·기존 BDD·선택한 Java 테스트 코드와 비교해 누락된 시나리오 후보를 제안하는 Spring Boot 애플리케이션입니다. 후보는 사람이 검토하고, Slack에서 승인한 결과를 GitHub Issue로 전달합니다.

## 처리 흐름

1. 오류 이벤트의 타입과 필드를 검증하고 스택을 기준으로 오류 그룹을 만듭니다.
2. 업무 조건과 분석 기준이 같은 작업은 재사용합니다.
3. 명세·BDD와 배포 커밋에 고정한 테스트 코드를 수집하고 분석 근거를 스냅샷으로 저장합니다.
4. 워커가 작업을 확보해 LLM을 호출하고 응답 구조와 근거 인용을 검증합니다.
5. 검토 화면 또는 Slack에서 후보를 승인·수정·반려합니다.
6. Slack 승인 후보를 GitHub Issue로 등록하거나 갱신합니다.

분석기는 대상 서비스의 테스트를 실행하거나 운영 코드를 자동으로 수정하지 않습니다.

## 코드 구성

소스 루트는 `src/main/java/com/example1/bddllm`입니다.

| 패키지 | 역할 |
| --- | --- |
| `event` | 오류 입력 검증, 스택 기반 그룹화 |
| `evidence` | 명세·BDD 매핑, GitHub 소스 수집, Java 테스트 추출·선별 |
| `job` | 오류·그룹·작업 등록, 작업 상태와 재처리 기록 |
| `analysis` | AI 공급자 연결, 프롬프트 구성, 응답·근거 인용 검증 |
| `worker` | 작업 확보, 실행 잠금, 재시도·복구·예약 처리 |
| `review` | 후보 검토 API, revision 기반 충돌 검사, 승인 BDD 출력 |
| `slack` | 알림 전달, HTTP 서명 검증, 버튼·모달 처리 |
| `github` | 승인 후보의 Issue 등록·갱신, 전달 상태와 중복 방지 |
| `evaluation` | 참고 응답 기반 평가, 성공 응답 캐시 |
| `config`, `common` | 역할 실행, 스키마 검증, 트랜잭션·JSON 처리 |

- `config/evidence.toml`: 서비스·메서드·라우트별 분석 자료 매핑
- `evidence/`: 분석에 사용하는 명세·BDD와 출처 자료
- `src/main/resources/prompts/`: 버전별 분석 프롬프트
- `src/main/resources/review-assets/`: 검토 화면
- `samples/`: 오류 접수 예시와 평가 입력
- `src/test/`: 자동 테스트와 테스트 fixture

## 핵심 동작

### 오류와 분석 작업의 중복 방지

- 이벤트 고유 기준은 `service + environment + event_id`입니다. 같은 ID의 내용이 달라지면 `409`를 반환합니다.
- 작업 고유 기준은 `group_id + condition_hash + basis_hash`입니다.
- 같은 기준의 반복 오류는 작업의 상태·시도 횟수·분석 결과를 유지한 채 재사용합니다.
- 업무 조건, 배포 SHA, 명세, BDD, 프롬프트, 공급자·모델 등 분석 기준이 바뀌면 새 작업을 생성합니다.
- 업무 조건의 `unknown`은 미확인 상태로 처리합니다.

오류·그룹·작업 등록은 하나의 트랜잭션입니다. PostgreSQL 고유 제약과 `ON CONFLICT`로 동시 접수의 중복 생성을 막습니다.

### 분석 근거 수집과 보존

GitHub 소스는 이벤트의 전체 40자리 `deploy_sha`에 고정해 읽습니다. 지정한 커밋이나 파일을 찾지 못하면 기본 브랜치나 최신 파일로 대체하지 않습니다.

JavaParser로 테스트 메서드, 클래스 문맥, 필드·초기화, lifecycle, helper·fixture를 추출합니다. API 경로·스택·지정 심볼·업무 용어로 우선순위를 정하고, 테스트와 준비 코드를 묶어서 선택합니다. 입력 예산을 넘는 묶음은 제외 목록에 기록합니다.

- 전체 분석 입력 상한: 60,000 Unicode codepoints
- 출력 상한: 6,000 tokens
- 선택 코드의 원본 위치·해시·제외 사유를 스냅샷으로 보존
- 워커는 등록 당시 스냅샷을 사용하며 GitHub에서 자료를 다시 수집하지 않음

정적 선별 결과는 선택한 근거 범위의 판단입니다. 저장소 전체의 테스트 부재나 대상 테스트의 실행·통과를 의미하지 않습니다.

### LLM 연결과 응답 검증

Spring AI의 `ChatModel` 인터페이스로 Ollama Cloud·DeepSeek·OpenAI 연결을 분리합니다. 공급자와 모델은 작업 등록 시 저장하며 다른 공급자로 자동 전환하지 않습니다.

응답은 JSON Schema, 허용 필드, 후보 구조와 인용 원문을 검증합니다. 테스트 근거는 `covered`, `weak_assertion`, `characterization_only`, `not_found_in_selected_evidence`, `inconclusive`로 구분합니다. 원문 인용의 일치 여부와 별개로 의미와 제품 정책은 검토자가 판단합니다.

프롬프트는 파일명과 본문 해시로 식별합니다. 변경 시 새 버전 파일을 추가하고 이전 버전도 보존합니다. 작업에 기록된 파일이 없거나 해시가 달라지면 LLM 호출 전에 실패합니다.

### 작업 실행·재시도·복구

- `FOR UPDATE SKIP LOCKED`와 실행 토큰으로 작업을 확보합니다.
- 공유 파일 잠금으로 분석부터 결과 저장까지 같은 작업의 중복 실행을 막습니다.
- LLM 호출 중에는 DB 트랜잭션을 유지하지 않습니다.
- 호출 제한·LLM 서버 오류는 최초 실행 포함 최대 3회 시도하며 `Retry-After`를 존중합니다.
- 타임아웃·연결 단절은 처리 여부가 불명확하므로 자동 재호출하지 않습니다.
- 상시 워커는 `Asia/Seoul` 매시간 정각에 소진된 일시 오류를 조회하고 작업당 추가 실행권을 1회 부여합니다.
- 중단 복구는 실행 잠금이 해제되고 5분 이상 지난 작업에만 허용합니다.

DB 접근은 Spring Data JPA와 `EntityManager`를 사용합니다. 작업 확보·결과 저장은 짧은 트랜잭션으로 나누고, 대량 갱신 뒤에는 영속성 컨텍스트를 정리합니다.

### 사람의 검토와 결과 전달

AI 원본은 보존하고 검토 이력을 새 revision으로 추가합니다. 오래된 revision의 검토 요청은 `409`로 거절합니다. 수정·반려에는 사유가 필요하고, 수정 저장 후에는 다시 검토 대기가 됩니다.

Slack 요청은 원본 본문·서명·타임스탬프를 검증하고 수신 확인 후 별도 스레드에서 처리합니다. 승인과 GitHub 전달 대기는 같은 DB 트랜잭션에 기록합니다.

GitHub Issue는 작업·후보별 전달 기록으로 중복을 막고 재승인 시 기존 Issue를 갱신합니다. 생성 응답이 끊기면 고유 표식으로 결과를 조회하며, 확인되지 않은 요청을 무조건 재전송하지 않습니다. 닫힌 Issue는 자동으로 다시 열지 않습니다.

## 실행 역할

프로젝트와 Docker 이미지는 하나이며 `BDD_ROLE`로 실행 기능을 선택합니다.

| 역할 | 실행 기능 | 기본 HTTP 포트 |
| --- | --- | --- |
| `api` | 오류 접수, 작업·검토 조회, 검토 화면 | 8000 |
| `worker` | 분석 작업 처리, 재시도·복구 | 없음 |
| `slack` | 알림 전달, 버튼·모달 HTTP 수신 | 8001 |
| `migrate` | DB 스키마 적용 후 종료 | 없음 |
| `tools` | 상태 조회, 수동 재시도·복구·평가 | 없음 |

## 주요 API

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| `POST` | `/events` | 오류 접수·분석 작업 등록 |
| `GET` | `/jobs/{id}` | 분석 상태·결과 조회 |
| `GET` | `/jobs/{id}/evidence` | 등록 당시 분석 근거 조회 |
| `GET` | `/review-jobs` | 검토할 작업 목록 |
| `GET` | `/jobs/{id}/review` | 후보별 검토 상태·이력 |
| `POST` | `/jobs/{id}/candidates/{number}/reviews` | 승인·수정·반려 |
| `GET` | `/jobs/{id}/approved.feature` | 현재 승인된 후보의 Gherkin 출력 |
| `GET` | `/health` | 애플리케이션·DB 상태 확인 |

오류 접수 예시:

```bash
curl -i http://localhost:8000/events \
  -H 'Content-Type: application/json' \
  --data-binary @samples/coupon_expired/event.json
```

새 작업은 `201`, 같은 기준의 기존 작업은 `200`을 반환합니다. 응답의 `job_created`로 구분합니다. 기본 검토 화면은 `http://localhost:8000/reviews`, API 문서는 `http://localhost:8000/docs`입니다.

## 실행 준비

Java 25와 Docker Compose를 사용하며 DB는 PostgreSQL 17입니다. Gradle은 프로젝트의 `./gradlew`를 사용합니다.

DB 접속 정보와 사용할 외부 서비스의 인증 정보는 로컬 `.env` 또는 실행 환경에 주입합니다. 설정 항목은 `compose.yaml`과 `src/main/resources/application.yaml`에서 확인할 수 있습니다. Compose는 `.env`를 읽으며 IDE·`bootRun`에서는 환경변수를 직접 전달합니다.

**DB 마이그레이션 SQL은 저장소에 포함하지 않습니다.** 새 DB 초기화와 DB 통합 테스트에는 `src/main/resources/db/migration/`의 SQL을 별도로 준비해야 합니다. 애플리케이션은 Flyway와 `ddl-auto=validate`로 스키마를 검증합니다. 로컬 SQL을 준비한 뒤 이미지를 빌드하면 해당 파일이 애플리케이션 리소스에 포함됩니다.

Compose가 참조하는 외부 네트워크 `bdd-error-reporting`을 준비한 다음 실행합니다.

```bash
docker compose build
docker compose up -d db
docker compose run --rm migrate
docker compose up -d api
```

분석 워커는 별도로 시작합니다. 기본 Compose 실행에서는 실제 분석을 자동으로 시작하지 않습니다.

```bash
docker compose run --rm worker --bdd.once=true
docker compose --profile analysis up -d worker
```

## 자동 테스트

```bash
./gradlew test
```

입력 검증·해시 일관성·동시 등록·실행 잠금·재시도·근거 인용·검토 이력·외부 연동을 검증합니다. 외부 LLM·Slack·GitHub Issue 호출은 모의 처리하며 DB 통합 테스트는 Docker와 로컬 마이그레이션 SQL을 사용합니다.

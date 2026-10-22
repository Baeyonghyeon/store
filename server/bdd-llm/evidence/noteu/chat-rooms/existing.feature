# language: ko
# 확인 범위: noteu 커밋 9bbbfdb77c9b7675015fd21e6061c704aa9748e2의 체크아웃.
# 대상: GET /subjects/{subject-id}/chats/rooms.
# src/test, load-gen, scripts, docs, .http, .feature, 비vendor JavaScript를 확인했다.
# 이 범위에서 실제 채팅방 업무 결과를 assertion으로 검증하는 자동 테스트와 기존 BDD 시나리오는 찾지 못했다.
# 다른 브랜치, 외부 저장소, 별도 QA 자료까지 테스트가 없다고 단정하지 않는다.
#
# JUnit 23개는 context 로딩 1개, 오류 수집 인터셉터 11개, 전송기 6개, 설정 5개다.
# 오류 수집 테스트는 가상 FixtureController / 응답 resolver / HTTP 수신 스텁을 사용한다.
# 실제 ChatRoomController 또는 RestChatService의 업무 동작을 검증한 근거로 계산하지 않는다.
# 근거: src/test/java/com/noteu/noteu/NoteuApplicationTests.java:17-24,
#       src/test/java/com/noteu/noteu/error/reporting/ErrorReportingInterceptorTest.java:39-52,203-265,
#       src/test/java/com/noteu/noteu/error/reporting/ErrorEventReporterTest.java,
#       src/test/java/com/noteu/noteu/error/reporting/ErrorReportingConfigTest.java.
#
# 실제 API 호출 자료: load-gen/rooms.mjs:24-53, load-gen/polling.mjs:15-44.
# 세션 쿠키로 목록을 호출하여 최종 응답의 res.ok(2xx) 여부를 집계하며 rooms는 소요 시간도 측정한다.
# fetch는 리다이렉트를 기본으로 따라간다. 원래 API의 200 JSON 반환을 검증한 것으로 해석하지 않는다.
# JSON 본문, 방 목록 내용, 참가자, 정렬, 권한은 검증하지 않는다.
# HTTP 실패는 오류 수에 집계하지만 오류 수가 있다는 이유만으로 실패 종료하지 않는다.
# scripts/measure-n1.sh:24-29는 DB 카운터로 평균 쿼리 수를 출력하며 자동 합격 기준 assertion은 없다.
# ChatHttpRequest.http:31-33은 수동 요청 예시이며 응답 assertion이 없다. 인증 원문은 포함하지 않는다.
#
# 과거 수동 확인: docs/N+1_검증_가이드.md:230에는 방 100개 기준 participants 빈 방 0건과 상대 정보 반환 기록이 있다.
# 같은 문서 :201-209는 확인할 항목 목록이며 실행 완료나 자동 테스트를 증명하지 않는다.
# :239에는 관련 수정이 '미착수'라는 오래된 문구가 남아 있어 :230의 완료 기록과 상충한다.
# 따라서 수동 기록/체크리스트를 자동화된 기존 시나리오로 바꾸거나 현재 실행 성공으로 계산하지 않는다.
#
# 발견하지 못한 업무 assertion: 비로그인/세션 만료의 기대 응답, 과목 없음/권한 없음 처리,
# DB 장애 처리, 응답 JSON 구조, 본인 참여 방 필터, 빈 방, 중복 방, 최근 메시지 정렬, 상대 참가자 정보.
# 제품의 기대 응답은 테스트 부재나 현재 예외 발생만으로 확정할 수 없다. 명세의 확인된 정책과 미확정 정책을 구분한다.
# 자세한 출처와 범위는 같은 디렉터리의 test-inventory.md에 기록했다.
# 이 파일은 감사 결과를 전달하는 입력이며 Gherkin 실행 바인딩이나 새 자동 테스트를 추가하지 않는다.
기능: 과목의 채팅방 목록 조회 — 확인된 기존 테스트 범위

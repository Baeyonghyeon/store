# noteu 과목별 채팅방 목록 조회 — 출처를 구분한 분석 명세

대상: `GET /subjects/{subject-id}/chats/rooms` · 서비스 `noteu`
확인한 코드 기준: `9bbbfdb77c9b7675015fd21e6061c704aa9748e2`

이 문서는 현재 저장소의 설계 문서, 실제 구현, 테스트 검토 결과를 연결한다.
아래에서 **개발 문서의 명시적 기대 동작**, **사용자가 확인한 정책**, **구현 관찰**을 구분한다.
구현 관찰이나 현재의 장애 응답을 의도한 제품 정책으로 해석할 근거는 없다.
출처 파일의 확인한 바이트 해시는 `source-index.json`에 기록한다. 파일 경로만 전달된 자료까지 분석기가 직접 읽은 것은 아니다.

## 1. 기존 개발 문서에 명시된 정상 조회 요구사항

다음은 `docs/N+1_검증_가이드.md:201-209`와 `docs/N+1_개선_설계.md:391-399`의 검증 항목을 구체화한 것이다.
이는 문서화된 기대 동작이며, 각 항목에 자동 테스트가 존재하거나 현재 통과했다는 뜻은 아니다.
로그인한 사용자의 정상 조회 조건에 적용한다. 인증·접근 권한 실패 조건에는 아래 정상 조회 규칙을 대신 적용하지 않는다.

- DOC-ROOM-01: 채팅방 목록에는 동일한 방 ID가 중복해서 포함되지 않는다.
  - 원문: `방 중복 반환 없음 (fetch join 중복 제거)`
- DOC-ROOM-02: 메시지가 없는 채팅방도 조회 대상 목록에 포함한다.
  - 원문: `빈 채팅방 표시` / `메시지 없는 방이 목록에 포함되는지`
- DOC-ROOM-03: 채팅방 목록은 마지막 메시지 시각의 내림차순으로 정렬한다.
  - 원문: `최근 메시지순 정렬 유지` / `마지막 메시지 시각 기준 내림차순`
- DOC-ROOM-04: 메시지가 없는 방의 lastMessage는 빈 문자열이고 lastMessageDateTime은 null이다.
  - 원문: `lastMessage=""`, `lastMessageDateTime=null`

참가자 정보에 관해서는 같은 검증 가이드 213-230행이 상대방 정보가 사라진 회귀를 설명하고, 방 100개에서 상대방 정보 반환을 확인했다고 기록한다.
다만 239행에는 수정이 미착수라는 오래된 문구도 남아 있다. 이 기록은 과거의 수동 확인 이력으로만 취급하며 자동 테스트 통과나 현재 제품의 모든 조건에 대한 보장으로 확대하지 않는다.

## 2. 사용자가 확인한 인증·접근 정책

확인 출처: 2026-10-17 이 프로젝트 대화에서 사용자가 직접 선택한 응답 정책.
이는 아래 구현 관찰과 별개의 **의도한 기대 동작**이다. 현재 코드가 이미 충족한다는 뜻은 아니다.

- AUTH-01: 비로그인 사용자 또는 세션이 만료된 사용자가 채팅방 목록 조회 API를 호출하면 로그인 페이지로 리다이렉트한다.
  - 사용자 답변 원문: `로그인 페이지로 리다이렉트`
  - 현재 서비스에 설정된 로그인 페이지 경로는 `/auth/login`이다 (`SecurityConfig.java:51-62`).
  - 이 정책은 JSON에 로그인 URL만 담아 반환하는 방식이 아니라 사용자가 선택한 리다이렉트 응답을 의미한다. 구체적인 3xx 상태 코드와 프런트 화면의 후속 처리는 이번 답변으로 확정하지 않았다.
- ACCESS-01: 과목이 존재하지 않으면 HTTP 404를 반환한다.
  - 사용자 답변 원문: `과목 없음은 404, 수강 권한 없음은 403`
- ACCESS-02: 로그인한 사용자에게 해당 과목의 수강 권한이 없으면 HTTP 403을 반환한다.
  - 사용자 답변 원문: `과목 없음은 404, 수강 권한 없음은 403`

현재 비로그인 요청에서 관측된 NullPointerException과 HTTP 500은 AUTH-01의 기대 동작과 다르다.
AUTH-01 조건의 BDD 기대 결과는 로그인 페이지 리다이렉트이며, 401 JSON을 제안할 근거는 없다.
구체적인 오류 코드·문구, 인증 실패와 과목 없음이 동시에 성립할 때의 우선순위는 미확정이다.

## 3. 실제 구현에서 관찰한 사실 — 기대 정책과 구분

### 요청 처리

`src/main/java/com/noteu/noteu/chat/controller/ChatRoomController.java:46-51`:

```java
@GetMapping("/rooms")
@ResponseBody
public ChatRoomInfoResponseDto getAllRoom(@PathVariable("subject-id") Long subjectId,
                                         @AuthenticationPrincipal MemberInfo memberInfo) {
    return restChatService.findAllById(subjectId, memberInfo.getId());
}
```

`SecurityConfig.java:41-42`는 `/**`를 `permitAll()`로 처리한다. 로그인 페이지 경로 `/auth/login` 설정만으로 위 JSON API의 인증 실패 정책까지 확정할 수 없다.
프런트엔드 `src/main/resources/static/js/chat/chat.js:164-175,301-316`은 이 API의 JSON을 사용하지만 401·로그인 이동 처리 규칙은 구현되어 있지 않다.

### 데이터 조회와 응답

- `ChatRoomRepositoryCustomImpl.java:23-44`: 요청한 과목 ID와 로그인 회원의 채팅방 참여를 조건으로 조회한다. 필터용 참가자 별칭과 응답 데이터용 fetch join을 분리하고 결과 방을 distinct로 정리한다. 이 메서드는 과목 존재·수강 권한을 별도로 검증하지 않는다.
- `RestChatServiceImpl.java:55-90`: 방 목록과 마지막 메시지를 조합하고, 마지막 메시지 시각 내림차순으로 정렬한다. 메시지가 없는 방의 null 시각은 뒤로 정렬한다.
- `ChatMessageRepositoryCustomImpl.java:18` 이하: 방마다 최대 메시지 ID를 가진 메시지를 조회한다. 최대 ID와 실제 작성 시각이 다른 경우의 제품 기준은 확인되지 않았다.
- `ChatRoomInfoResponseDto.java:14-16`: 응답 필드는 `chatRoomResponseDtos`와 `loginId`다.
- `ChatRoomResponseDto.java:17-22`: 방 응답에는 `id`, `subjectId`, `lastMessage`, `lastMessageDateTime`, `participants`가 있다.
- `ChatConverter.java:18-28`: 참가자 중 로그인 회원 자신을 제외한 회원 정보를 응답 DTO로 변환한다. 이 구현 사실만으로 모든 회원 필드의 노출 정책을 승인한 것은 아니다.

위 Java 파일의 전체 경로와 해시는 `source-index.json`에서 확인할 수 있다. 이 절은 읽은 코드의 발췌·관찰이며, 제품 기대 동작으로 인용할 때는 1절의 개발 문서 또는 확인된 사용자 정책과 구분해야 한다.

## 4. 기존 검증 자료의 범위

`existing.feature`의 주석에 실제 테스트·측정·수동 확인의 출처와 한계를 넣었다.
현재 체크아웃에서 이 API의 자동 업무 BDD나 응답 JSON을 검증하는 업무 JUnit은 찾지 못했다.
전체 저장소 밖의 테스트, 다른 브랜치, 외부 테스트 저장소까지 없다는 결론은 아니다.

## 5. 추가로 미확정인 세부 사항

- 같은 마지막 메시지 시각의 방 사이의 정렬 우선순위.
- DB 장애 시 상태 코드, 사용자 안내 문구, 재시도 안내.
- 리다이렉트의 구체적 3xx 상태 코드, 프런트 화면의 후속 행동, 403·404 오류 응답의 본문 형식·코드·문구.

이 항목들은 이번 연결 작업에서 임의로 채우지 않는다.

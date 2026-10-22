"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const statusNames = { pending: "검토 대기", approved: "승인", rejected: "반려" };
  const actionNames = { edit: "수정", approve: "승인", reject: "반려" };
  const state = { jobs: [], next: null, jobId: null, detail: null, selection: 0, listRequest: 0, drafts: new Map(), busy: new Set(), feedback: new Map() };
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const keyFor = (jobId, candidateNo) => `${jobId}:${candidateNo}`;
  const dateText = (value) => {
    if (!value) return "시간 정보 없음";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "시간 정보 없음" : date.toLocaleString("ko-KR", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  };
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }
  function button(text, className, handler) {
    const node = el("button", `button ${className}`, text);
    node.type = "button";
    node.addEventListener("click", handler);
    return node;
  }
  function field(labelText, id, value, multiline, onInput) {
    const wrap = el("div", "editor-field");
    const label = el("label", "", labelText);
    label.htmlFor = id;
    const input = el(multiline ? "textarea" : "input");
    input.id = id;
    input.value = value;
    if (!multiline) input.type = "text";
    input.addEventListener("input", () => onInput(input.value));
    wrap.append(label, input);
    return { wrap, input };
  }
  async function api(path, options = {}) {
    let response;
    try { response = await fetch(path, { ...options, headers: { ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers } }); }
    catch { throw new Error("서버에 연결할 수 없습니다. API 실행 상태를 확인한 뒤 다시 시도해 주세요."); }
    if (!response.ok) {
      let payload;
      try { payload = await response.json(); } catch { /* The server may return a non-JSON error. */ }
      const error = new Error(payload?.detail?.message || `요청을 완료하지 못했습니다. (HTTP ${response.status})`);
      error.status = response.status;
      error.code = payload?.detail?.code;
      throw error;
    }
    return response;
  }
  function empty(title, description, retry) {
    const wrap = el("div", "empty-state");
    wrap.append(el("span", "empty-mark", "⋯"), el("h2", "", title), el("p", "", description));
    if (retry) wrap.append(button("다시 불러오기", "button-secondary", retry));
    return wrap;
  }
  function renderJobs() {
    $("job-list").replaceChildren();
    for (const job of state.jobs) {
      const item = button("", "job-list-item", () => selectJob(job.job_id));
      item.className = "job-list-item";
      if (job.job_id === state.jobId) item.setAttribute("aria-current", "true");
      const heading = el("span", "job-item-heading");
      heading.append(el("span", "", job.service), el("span", "job-id", `#${job.job_id}`));
      item.append(heading, el("span", "job-route", `${job.method} ${job.route}`));
      item.append(el("span", "job-counts", `대기 ${job.pending_count} · 승인 ${job.approved_count} · 반려 ${job.rejected_count}`));
      item.append(el("span", "job-time", `${job.environment} · ${dateText(job.finished_at)}`));
      $("job-list").append(item);
    }
    $("load-more").hidden = !state.next;
  }
  async function loadJobs(append = false) {
    const request = ++state.listRequest;
    $("refresh-jobs").disabled = true;
    $("load-more").disabled = true;
    $("jobs-status").textContent = "분석 목록을 불러오는 중…";
    try {
      const query = append && state.next ? `&before_id=${encodeURIComponent(state.next)}` : "";
      const data = await (await api(`/review-jobs?limit=30${query}`)).json();
      if (request !== state.listRequest) return;
      state.jobs = append ? [...state.jobs, ...data.items.filter((item) => !state.jobs.some((existing) => existing.job_id === item.job_id))] : data.items;
      state.next = data.next_before_id;
      renderJobs();
      $("jobs-status").textContent = state.jobs.length ? "" : "아직 완료된 분석이 없습니다.";
      if (!state.jobId && state.jobs.length) await selectJob(state.jobs[0].job_id);
      else if (!state.jobId) $("review-detail").replaceChildren(empty("검토할 분석이 아직 없습니다", "오류 분석이 완료되면 이곳에서 BDD 후보를 검토할 수 있습니다."));
    } catch (error) {
      if (request !== state.listRequest) return;
      $("jobs-status").textContent = error.message;
      if (!state.jobId) $("review-detail").replaceChildren(empty("목록을 불러오지 못했습니다", error.message, () => loadJobs()));
    } finally {
      if (request === state.listRequest) { $("refresh-jobs").disabled = false; $("load-more").disabled = false; }
    }
  }
  function updateJobCounts(data) {
    const job = state.jobs.find((item) => item.job_id === data.job_id);
    if (job) {
      for (const status of ["pending", "approved", "rejected"]) job[`${status}_count`] = data.candidates.filter((candidate) => candidate.status === status).length;
      renderJobs();
    }
  }
  async function selectJob(jobId, preserve = false) {
    const selection = ++state.selection;
    state.jobId = jobId;
    if (!preserve) state.detail = null;
    renderJobs();
    const url = new URL(window.location.href);
    url.searchParams.set("job", String(jobId));
    window.history.replaceState(null, "", url);
    $("review-detail").setAttribute("aria-busy", "true");
    if (!preserve) $("review-detail").replaceChildren(empty(`작업 #${jobId}를 불러오는 중입니다`, "명세와 기존 BDD, 생성된 후보를 확인합니다."));
    try {
      const data = await (await api(`/jobs/${encodeURIComponent(jobId)}/review`)).json();
      if (selection !== state.selection || state.jobId !== jobId) return;
      state.detail = data;
      updateJobCounts(data);
      renderDetail();
      return data;
    } catch (error) {
      if (selection !== state.selection || state.jobId !== jobId) return;
      if (preserve && state.detail) {
        const notice = el("p", "feedback feedback-error", error.message);
        notice.setAttribute("role", "alert");
        $("review-detail").prepend(notice);
      } else $("review-detail").replaceChildren(empty("분석을 불러오지 못했습니다", error.message, () => selectJob(jobId)));
    } finally {
      if (selection === state.selection) $("review-detail").setAttribute("aria-busy", "false");
    }
  }
  function scenario(candidate) {
    const wrap = el("div", "scenario");
    function row(keyword, content, quote) {
      const line = el("div", "scenario-row");
      const text = el("div", "scenario-content", content);
      if (quote) { const block = el("blockquote", "spec-quote"); block.append(el("span", "", "명세 근거"), document.createTextNode(quote)); text.append(block); }
      line.append(el("div", "scenario-keyword", keyword), text);
      wrap.append(line);
    }
    candidate.given.forEach((text, index) => row(index === 0 ? "Given" : "And", text));
    row("When", candidate.when);
    candidate.then.forEach((outcome, index) => row(index === 0 ? "Then" : "And", outcome.behavior, outcome.specification_quote));
    return wrap;
  }
  function renderDetail() {
    const data = state.detail;
    if (!data || data.job_id !== state.jobId) return;
    const container = $("review-detail");
    container.replaceChildren();
    const heading = el("div", "detail-heading");
    const title = el("div");
    title.append(el("p", "eyebrow", `분석 #${data.job_id} · ${data.environment}`), el("h2", "", data.service), el("p", "detail-route", `${data.method} ${data.route}`));
    title.append(el("p", "detail-meta", `${dateText(data.finished_at)} 분석 완료 · 배포 ${String(data.deploy_sha).slice(0, 12)}`));
    const tools = el("div");
    const actions = el("div", "detail-tools");
    const refresh = button("최신 내용 보기", "button-secondary", () => selectJob(data.job_id, true));
    actions.append(refresh);
    tools.append(actions, el("p", "download-note", "Slack에서 승인하면 연결된 저장소에 GitHub Issue가 자동 등록됩니다."));
    heading.append(title, tools);
    const summary = el("section", "analysis-summary");
    summary.append(el("h3", "section-label", "관측된 오류와 분석"), el("p", "", data.summary), el("p", "assessment", data.existing_bdd_assessment));
    container.append(heading, summary);
    if (data.questions.length) {
      const questions = el("section", "questions");
      const list = el("ul");
      data.questions.forEach((question) => list.append(el("li", "", question)));
      questions.append(el("h3", "", "먼저 확인할 제품 정책"), list);
      container.append(questions);
    }
    const evidence = el("details", "evidence");
    evidence.append(el("summary", "", "분석에 사용한 제품 명세 · 기존 BDD 보기"));
    const columns = el("div", "evidence-columns");
    for (const [name, content] of [["제품 명세", data.specification], ["기존 BDD", data.bdd]]) {
      const column = el("div");
      column.append(el("h4", "", name), el("pre", "document-text", content));
      columns.append(column);
    }
    evidence.append(columns);
    container.append(evidence);
    if (data.test_evidence) {
      const tests = data.test_evidence;
      const panel = el("details", "evidence test-evidence");
      panel.append(el("summary", "", `실제 테스트 근거 · 선택 ${tests.selected_tests.length} / 발견 ${tests.discovered_test_count} · 제외 ${tests.excluded_tests.length}`));
      panel.append(el("p", "", `${tests.repository} @ ${tests.commit_sha}`), el("p", "", tests.scope_note));
      const link = el("a", "", "수집 근거 JSON 보기");
      link.href = `/jobs/${data.job_id}/evidence`;
      panel.append(link);
      for (const assessment of data.test_assessments || []) {
        panel.append(el("h4", "", `${assessment.condition} · ${assessment.status}`), el("p", "", assessment.explanation));
        for (const citation of assessment.citations) {
          const source = tests.chunks.find((chunk) => chunk.id === citation.chunk_id);
          panel.append(el("p", "", source ? `${source.path}:${source.start_line}–${source.end_line}` : citation.chunk_id), el("pre", "document-text", citation.quote));
        }
      }
      for (const test of tests.selected_tests) {
        const entry = el("details");
        entry.append(el("summary", "", `${test.method}${test.disabled ? " (Disabled)" : ""}`));
        for (const issue of test.issues) entry.append(el("p", "", issue));
        for (const id of test.chunk_ids) {
          const chunk = tests.chunks.find((item) => item.id === id);
          if (chunk) entry.append(el("h4", "", `${chunk.path}:${chunk.start_line}–${chunk.end_line} · ${chunk.kind}`), el("pre", "document-text", chunk.content));
        }
        panel.append(entry);
      }
      for (const excluded of tests.excluded_tests) panel.append(el("p", "", `제외 · ${excluded.id}: ${excluded.reason}`));
      for (const warning of tests.warnings) panel.append(el("p", "", warning));
      container.append(panel);
    }
    const candidateHeading = el("div", "candidates-heading");
    candidateHeading.append(el("h3", "", "검토할 시나리오"), el("span", "count", data.candidates.length));
    container.append(candidateHeading);
    if (!data.candidates.length) container.append(empty("제안된 시나리오가 없습니다", data.questions.length ? "위의 제품 정책 질문을 먼저 확인해 주세요." : "이 분석에서는 새로운 BDD 후보를 제안하지 않았습니다."));
    data.candidates.forEach((candidate) => container.append(candidateCard(data.job_id, candidate)));
  }
  function getDraft(jobId, review) {
    const key = keyFor(jobId, review.candidate_no);
    if (!state.drafts.has(key)) state.drafts.set(key, { editing: false, reason: "", candidate: clone(review.current), baseRevision: review.revision, conflict: false });
    const draft = state.drafts.get(key);
    if (!draft.editing && !draft.conflict && !state.busy.has(key)) {
      draft.baseRevision = review.revision;
      draft.candidate = clone(review.current);
    }
    if (draft.editing && !state.busy.has(key) && draft.baseRevision !== review.revision) {
      draft.conflict = true;
      state.feedback.set(key, { kind: "error", text: "수정 중인 후보에 새로운 검토가 있습니다. 작성 내용은 유지했습니다. 최신 내용을 확인한 뒤 계속해 주세요." });
    }
    return draft;
  }
  function candidateCard(jobId, review) {
    const key = keyFor(jobId, review.candidate_no);
    const draft = getDraft(jobId, review);
    const busy = state.busy.has(key);
    const card = el("article", "candidate");
    card.id = `candidate-${review.candidate_no}`;
    card.setAttribute("aria-labelledby", `${card.id}-title`);
    if (busy) card.setAttribute("aria-busy", "true");
    const top = el("div", "candidate-top");
    const kicker = el("div", "candidate-kicker");
    kicker.append(el("span", `status status-${review.status}`, statusNames[review.status]), el("span", "", review.current.gap_type === "missing_scenario" ? "누락된 시나리오" : "검증 항목 보강"), el("span", "", `후보 ${review.candidate_no} · 수정본 ${review.revision}`));
    const heading = el("h3", "", review.current.title);
    heading.id = `${card.id}-title`;
    top.append(kicker, heading, el("p", "candidate-reason", review.current.reason));
    card.append(top, scenario(review.current));
    if (review.github_issue) {
      const issue = review.github_issue;
      const stateNames = {pending:"등록 대기", processing:"등록 확인 중", published:"등록 완료", uncertain:"전송 결과 확인 필요", failed:"등록 실패", cancelled:"최신 승인 대기"};
      const issueSection = el("div", "source-quotes");
      issueSection.append(el("p", "", `GitHub Issue · ${stateNames[issue.state] || issue.state} · ${issue.repository}`));
      if (issue.error_code) issueSection.append(el("p", "", issue.error_code));
      if (issue.url) {
        const link = el("a", "button-secondary", `Issue #${issue.number} 보기`);
        link.href = issue.url; link.target = "_blank"; link.rel = "noopener noreferrer";
        issueSection.append(link);
      }
      card.append(issueSection);
    }

    if (review.current.existing_bdd_quotes.length) {
      const quotes = el("div", "source-quotes", "관련 기존 BDD");
      review.current.existing_bdd_quotes.forEach((quote) => quotes.append(el("blockquote", "", quote)));
      card.append(quotes);
    }
    const original = el("details", "original");
    original.append(el("summary", "", "AI 최초 제안과 비교하기"), el("p", "original-title", review.original.title), scenario(review.original));
    card.append(original);
    if (draft.editing) card.append(editor(jobId, review, draft, busy));
    const area = el("div", "review-area");
    const reasonLabel = el("label", "", "검토 의견");
    reasonLabel.htmlFor = `reason-${jobId}-${review.candidate_no}`;
    const reason = el("textarea", "review-reason");
    reason.id = reasonLabel.htmlFor;
    reason.maxLength = 2000;
    reason.value = draft.reason;
    reason.disabled = busy;
    reason.placeholder = "수정하거나 반려하는 이유를 남겨 주세요.";
    reason.addEventListener("input", () => { draft.reason = reason.value; });
    const help = el("p", "field-help", "수정·반려에는 의견이 필요합니다. 수정 저장 후에는 다시 검토 대기로 전환됩니다.");
    help.id = `${reason.id}-help`;
    reason.setAttribute("aria-describedby", help.id);
    const controls = el("div", "review-actions");
    if (draft.editing) {
      controls.append(button("수정 취소", "button-secondary", () => { draft.editing = false; draft.candidate = clone(review.current); draft.baseRevision = review.revision; draft.conflict = false; state.feedback.delete(key); renderDetail(); }));
      controls.append(button(busy ? "저장 중…" : "수정 저장", "button-primary", () => submitReview(jobId, review, "edit")));
    } else {
      controls.append(button("수정", "button-secondary", () => { draft.editing = true; draft.candidate = clone(review.current); draft.baseRevision = review.revision; state.feedback.delete(key); renderDetail(); $(`title-${key}`)?.focus(); }));
      const reject = button("반려", "button-danger", () => submitReview(jobId, review, "reject"));
      reject.disabled = review.status === "rejected";
      const approve = button("승인", "button-primary", () => submitReview(jobId, review, "approve"));
      approve.disabled = review.status === "approved";
      controls.append(reject, approve);
    }
    for (const control of controls.children) if (busy || draft.conflict) control.disabled = true;
    const feedback = el("div", "feedback");
    feedback.setAttribute("role", "status");
    feedback.setAttribute("aria-live", "polite");
    const message = state.feedback.get(key);
    if (message) {
      feedback.className += ` feedback-${message.kind}`;
      feedback.textContent = message.text;
    }
    if (draft.conflict) {
      const refresh = button("최신 내용 불러오기 · 작성 내용 유지", "button-secondary", () => resolveConflict(jobId, review.candidate_no));
      refresh.disabled = busy;
      feedback.append(refresh);
    }
    area.append(reasonLabel, reason, help, controls, feedback);
    card.append(area, reviewHistory(review));
    return card;
  }
  function editor(jobId, review, draft, busy) {
    const key = keyFor(jobId, review.candidate_no);
    const wrap = el("div", "editor");
    wrap.append(el("p", "editor-intro", "필요한 동작을 수정하고, 각 Then의 근거를 제품 명세에서 그대로 인용해 주세요."));
    const title = field("시나리오 제목", `title-${key}`, draft.candidate.title, false, (value) => { draft.candidate.title = value; });
    const given = field("Given · 사전 조건 (한 줄에 하나)", `given-${key}`, draft.candidate.given.join("\n"), true, (value) => { draft.candidate.given = value.split("\n"); });
    const when = field("When · 수행할 행동", `when-${key}`, draft.candidate.when, true, (value) => { draft.candidate.when = value; });
    wrap.append(title.wrap, given.wrap, when.wrap, el("h4", "", "Then · 확인할 결과와 명세 근거"));
    draft.candidate.then.forEach((outcome, index) => {
      const row = el("div", "then-editor");
      const heading = el("div", "then-editor-heading");
      const remove = button("삭제", "button-plain button-small", () => { draft.candidate.then.splice(index, 1); renderDetail(); });
      remove.disabled = busy || draft.candidate.then.length === 1;
      remove.setAttribute("aria-label", `Then ${index + 1} 삭제`);
      heading.append(el("h4", "", `Then ${index + 1}`), remove);
      const behavior = field("기대 동작", `behavior-${key}-${index}`, outcome.behavior, true, (value) => { outcome.behavior = value; });
      const quote = field("명세 원문 인용", `quote-${key}-${index}`, outcome.specification_quote, true, (value) => { outcome.specification_quote = value; });
      row.append(heading, behavior.wrap, quote.wrap);
      wrap.append(row);
    });
    wrap.append(button("+ 검증 항목 추가", "button-secondary button-small", () => { draft.candidate.then.push({ behavior: "", specification_quote: "" }); renderDetail(); $(`behavior-${key}-${draft.candidate.then.length - 1}`)?.focus(); }));
    if (busy) wrap.querySelectorAll("input, textarea, button").forEach((control) => { control.disabled = true; });
    return wrap;
  }
  function reviewHistory(review) {
    const history = el("details", "history");
    history.append(el("summary", "", `검토 이력 ${review.history.length}건`));
    if (!review.history.length) { history.append(el("p", "history-list muted", "아직 검토 이력이 없습니다.")); return history; }
    const list = el("ol", "history-list");
    [...review.history].sort((a, b) => b.revision - a.revision).forEach((entry) => {
      const item = el("li", "history-item");
      const meta = el("div", "history-meta");
      meta.append(el("strong", "", `${actionNames[entry.action]} · ${entry.reviewer}`), el("span", "history-time", `수정본 ${entry.revision} · ${dateText(entry.created_at)}`));
      item.append(meta, el("p", "history-reason", entry.reason || "추가 의견 없음"));
      const snapshot = el("details", "history-snapshot");
      snapshot.append(el("summary", "", "이 시점의 후보 보기"), el("p", "original-title", entry.candidate.title), scenario(entry.candidate));
      item.append(snapshot);
      list.append(item);
    });
    history.append(list);
    return history;
  }
  function localError(key, text) {
    state.feedback.set(key, { kind: "error", text });
    renderDetail();
  }
  async function submitReview(jobId, review, action) {
    const key = keyFor(jobId, review.candidate_no);
    const draft = getDraft(jobId, review);
    if (state.busy.has(key) || draft.conflict) return;
    const reviewer = $("reviewer").value.trim();
    if (!reviewer) { localError(key, "검토 이력에 남길 이름을 먼저 입력해 주세요."); $("reviewer").focus(); return; }
    if ((action === "edit" || action === "reject") && !draft.reason.trim()) { localError(key, "수정·반려 이유를 검토 의견에 입력해 주세요."); $(`reason-${jobId}-${review.candidate_no}`)?.focus(); return; }
    const body = { action, reviewer, reason: draft.reason.trim(), expected_revision: draft.baseRevision };
    if (action === "edit") {
      const candidate = clone(draft.candidate);
      candidate.title = candidate.title.trim();
      candidate.given = candidate.given.map((line) => line.trim()).filter(Boolean);
      candidate.when = candidate.when.trim();
      candidate.then = candidate.then.map((outcome) => ({ behavior: outcome.behavior.trim(), specification_quote: outcome.specification_quote.trim() }));
      if (!candidate.title || !candidate.given.length || !candidate.when || !candidate.then.length || candidate.then.some((outcome) => !outcome.behavior || !outcome.specification_quote)) { localError(key, "제목, Given, When과 각 Then의 기대 동작·명세 근거를 모두 입력해 주세요."); return; }
      body.candidate = candidate;
    }
    state.busy.add(key);
    state.feedback.delete(key);
    renderDetail();
    const selection = state.selection;
    let saved = false;
    try {
      await api(`/jobs/${jobId}/candidates/${review.candidate_no}/reviews`, { method: "POST", body: JSON.stringify(body) });
      saved = true;
      state.drafts.delete(key);
      state.feedback.set(key, { kind: "success", text: action === "edit" ? "수정 내용을 저장했습니다. 검토 대기 상태이므로 확인 후 승인해 주세요." : `${actionNames[action]}했습니다. 검토 이력에 기록되었습니다.` });
      if (state.jobId === jobId && state.selection === selection) await selectJob(jobId, true);
    } catch (error) {
      if (error.status === 409) {
        draft.conflict = true;
        state.feedback.set(key, { kind: "error", text: "다른 검토로 내용이 변경되었습니다. 작성 중인 내용은 유지했습니다. 최신 내용을 불러와 비교한 뒤 다시 저장해 주세요." });
      } else state.feedback.set(key, { kind: "error", text: error.message });
    } finally {
      state.busy.delete(key);
      if (state.jobId === jobId) {
        // A successful write followed by a failed refresh must not allow a stale second write.
        if (saved && state.detail?.candidates.find((item) => item.candidate_no === review.candidate_no)?.revision === review.revision) {
          const stale = getDraft(jobId, review);
          stale.conflict = true;
          state.feedback.set(key, { kind: "info", text: "검토는 저장됐지만 최신 내용을 가져오지 못했습니다. 최신 내용을 불러온 후 계속해 주세요." });
        }
        renderDetail();
      }
    }
  }
  async function resolveConflict(jobId, candidateNo) {
    const key = keyFor(jobId, candidateNo);
    if (state.busy.has(key)) return;
    state.busy.add(key);
    const data = await selectJob(jobId, true);
    const latest = data?.candidates.find((item) => item.candidate_no === candidateNo);
    if (latest) {
      const draft = getDraft(jobId, latest);
      draft.baseRevision = latest.revision;
      draft.conflict = false;
      if (!draft.editing) draft.candidate = clone(latest.current);
      state.feedback.set(key, { kind: "info", text: "최신 내용을 불러왔습니다. 작성 중인 내용은 유지했으니 변경 사항을 비교한 후 다시 검토해 주세요." });
    }
    state.busy.delete(key);
    if (state.jobId === jobId) renderDetail();
  }

  try { $("reviewer").value = localStorage.getItem("bdd-reviewer") || ""; } catch { /* Storage may be disabled by the browser. */ }
  $("reviewer").addEventListener("input", () => { try { localStorage.setItem("bdd-reviewer", $("reviewer").value); } catch { /* The review itself does not depend on browser storage. */ } });
  $("refresh-jobs").addEventListener("click", () => loadJobs());
  $("load-more").addEventListener("click", () => loadJobs(true));
  const requested = new URLSearchParams(window.location.search).get("job");
  if (requested && /^[1-9]\d*$/.test(requested) && Number.isSafeInteger(Number(requested))) selectJob(Number(requested));
  loadJobs();
})();

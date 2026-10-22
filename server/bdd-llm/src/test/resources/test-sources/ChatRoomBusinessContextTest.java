package com.noteu.noteu.error.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteu.noteu.chat.controller.ChatRoomController;
import com.noteu.noteu.chat.service.RestChatService;
import com.noteu.noteu.member.dto.MemberInfo;
import com.noteu.noteu.sse.SseEmitterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatRoomBusinessContextTest {
    private ErrorEventReporter reporter;
    private RestChatService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        reporter = mock(ErrorEventReporter.class);
        service = mock(RestChatService.class);
        ErrorReportingProperties properties = new ErrorReportingProperties();
        properties.setService("noteu");
        properties.setEnvironment("test");
        properties.setDeploySha("test-commit");
        mvc = MockMvcBuilders.standaloneSetup(new ChatRoomController(service, mock(SseEmitterService.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addInterceptors(new ErrorReportingInterceptor(properties, reporter))
                .setHandlerExceptionResolvers(new ErrorCaptureResolver(), (request,response,handler,exception) -> {
                    response.setStatus(500);
                    try { response.getWriter().write("existing-error-response"); }
                    catch (java.io.IOException e) { throw new IllegalStateException(e); }
                    return new ModelAndView();
                }).build();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void actualAnonymousControllerFailureKeepsResponseAndRecordsOnlyObservedStates() throws Exception {
        mvc.perform(get("/subjects/1/chats/rooms").header("X-Auth-State", "authenticated"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("existing-error-response"));
        ErrorEvent event = onlyEvent();
        assertThat(event.error().type()).isEqualTo("java.lang.NullPointerException");
        assertThat(event.businessContext()).isEqualTo(new ChatRoomBusinessContext("anonymous", "unknown", "unknown"));
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedFailurePreservesObservedSubjectWithoutInferringEnrollment() throws Exception {
        authenticate();
        when(service.findAllById(1L, 987654321L)).thenAnswer(invocation -> {
            ChatRoomBusinessContext.observeExistingSubject();
            throw new IllegalStateException("private-exception-detail");
        });
        mvc.perform(get("/subjects/1/chats/rooms"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("existing-error-response"));
        ErrorEvent event = onlyEvent();
        assertThat(event.businessContext()).isEqualTo(new ChatRoomBusinessContext("authenticated", "exists", "unknown"));
        assertThat(new ObjectMapper().writeValueAsString(event))
                .doesNotContain("987654321", "private-user", "private-exception-detail");
    }

    @Test
    void invalidSessionIdentifierDoesNotClaimToKnowWhyAuthenticationIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestedSessionId("private-session");
        request.setRequestedSessionIdValid(false);
        ChatRoomBusinessContext.initialize(request);
        assertThat(ChatRoomBusinessContext.snapshot(request).authState()).isEqualTo("anonymous");
    }

    @Test
    void serviceOutsideReportingRequestCannotCreateContextOrLeakToAnotherRequest() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        ChatRoomBusinessContext.initialize(first);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(first));
        ChatRoomBusinessContext.observeExistingSubject();
        assertThat(ChatRoomBusinessContext.snapshot(first).subjectState()).isEqualTo("exists");
        MockHttpServletRequest next = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(next));
        ChatRoomBusinessContext.observeExistingSubject();
        assertThat(ChatRoomBusinessContext.snapshot(next)).isNull();
        RequestContextHolder.resetRequestAttributes();
        ChatRoomBusinessContext.observeExistingSubject();
    }

    @Test
    void businessContextIsOnlyInitializedForTheExactChatListGetRoute() throws Exception {
        // Another real controller route still retains the old event schema.
        mvc.perform(get("/subjects/1/chats/rooms/single"))
                .andExpect(status().isInternalServerError());
        ErrorEvent event = onlyEvent();
        assertThat(event.businessContext()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(event)).doesNotContain("business_context");
    }

    private void authenticate() {
        MemberInfo member = MemberInfo.builder().id(987654321L).username("private-user").build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(member, null, "ROLE_STUDENT"));
    }

    private ErrorEvent onlyEvent() {
        ArgumentCaptor<ErrorEvent> captor = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(reporter,times(1)).report(captor.capture());
        return captor.getValue();
    }
}

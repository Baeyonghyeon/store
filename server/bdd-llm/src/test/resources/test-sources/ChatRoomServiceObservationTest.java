package com.noteu.noteu.error.reporting;

import com.noteu.noteu.chat.converter.ChatConverter;
import com.noteu.noteu.chat.entity.ChatRoom;
import com.noteu.noteu.chat.repository.ChatMessageRepository;
import com.noteu.noteu.chat.repository.ChatParticipantRepository;
import com.noteu.noteu.chat.repository.ChatRoomRepository;
import com.noteu.noteu.chat.service.impl.RestChatServiceImpl;
import com.noteu.noteu.member.repository.MemberRepository;
import com.noteu.noteu.subject.repository.SubjectMemberRepository;
import com.noteu.noteu.subject.repository.SubjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatRoomServiceObservationTest {
    private MockHttpServletRequest request;
    private ChatRoomRepository rooms;
    private ChatMessageRepository messages;
    private SubjectRepository subjects;
    private SubjectMemberRepository enrollments;
    private RestChatServiceImpl service;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ChatRoomBusinessContext.initialize(request);
        rooms = mock(ChatRoomRepository.class);
        messages = mock(ChatMessageRepository.class);
        subjects = mock(SubjectRepository.class);
        enrollments = mock(SubjectMemberRepository.class);
        service = new RestChatServiceImpl(rooms, messages, mock(ChatParticipantRepository.class),
                subjects, enrollments, mock(MemberRepository.class), mock(ChatConverter.class));
    }

    @AfterEach
    void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void nonemptySubjectFilteredResultRecordsExistenceBeforeLaterFailureWithoutExtraQueries() {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(5L);
        when(rooms.findAllBySubjectId(1L,2L)).thenReturn(List.of(room));
        IllegalStateException original = new IllegalStateException("later query failure");
        when(messages.findLastMessagesByRoomIds(List.of(5L))).thenThrow(original);
        assertThatThrownBy(() -> service.findAllById(1L,2L)).isSameAs(original);
        assertThat(ChatRoomBusinessContext.snapshot(request).subjectState()).isEqualTo("exists");
        assertThat(ChatRoomBusinessContext.snapshot(request).enrollmentState()).isEqualTo("unknown");
        verifyNoInteractions(subjects, enrollments);
    }

    @Test
    void emptyRoomListDoesNotInferMissingSubjectOrEnrollmentAndPreservesResponse() {
        when(rooms.findAllBySubjectId(1L,2L)).thenReturn(List.of());
        var result = service.findAllById(1L,2L);
        assertThat(result.getChatRoomResponseDtos()).isEmpty();
        assertThat(result.getLoginId()).isEqualTo(2L);
        assertThat(ChatRoomBusinessContext.snapshot(request).subjectState()).isEqualTo("unknown");
        assertThat(ChatRoomBusinessContext.snapshot(request).enrollmentState()).isEqualTo("unknown");
        verifyNoInteractions(subjects, enrollments, messages);
    }
}

package kr.co.ktscholarship.backend.scholar.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties={"app.login-mail.enabled=true","app.login-mail.login-url=https://example.test/login","spring.mail.host=localhost","app.login-mail.worker-enabled=false","management.health.mail.enabled=false"})
@ActiveProfiles("test")
class LoginNoticeTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired LoginNoticeWorker worker;
    @MockitoSpyBean LoginNoticeRepository repository;
    @Autowired LoginNoticeService service;
    @Autowired PasswordEncoder encoder;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO users(id,name,email,password_hash,role,password_change_required,status) VALUES (9510,'담당자','notice-manager@example.test','old','MANAGER',FALSE,'ACTIVE'),(9511,'학생','notice-student@example.test','old','SCHOLAR',TRUE,'ACTIVE'),(9512,'변경 학생','notice-changed@example.test','old','SCHOLAR',FALSE,'ACTIVE'),(9513,'잠긴 학생','notice-locked@example.test','old','SCHOLAR',TRUE,'LOCKED')").update();
        jdbc.sql("UPDATE users SET locked_until='2099-01-01' WHERE id=9513").update();
        repository.saveSender(new LoginNoticeModels.Sender("foundation@example.test","재단"));
        when(mail.createMimeMessage()).thenAnswer(invocation->new MimeMessage(Session.getInstance(new Properties())));
    }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM audit_logs WHERE actor_id=9510").update();
        jdbc.sql("DELETE FROM scholar_login_notices WHERE requested_by=9510").update();
        jdbc.sql("DELETE FROM refresh_tokens WHERE user_id BETWEEN 9510 AND 9513").update();
        jdbc.sql("DELETE FROM users WHERE id BETWEEN 9510 AND 9513").update();
        jdbc.sql("DELETE FROM login_notice_settings").update();
    }
    @Test void sendsOnlyAfterButtonAndStoresHashBeforeSmtp() throws Exception {
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511]}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.queued").value(1));
        verify(mail,never()).send(any(MimeMessage.class));
        doAnswer(invocation->{
            var message=(MimeMessage)invocation.getArgument(0);
            String password=password(message);
            assertThat(password).matches("[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789]{12}");
            assertThat(encoder.matches(password,jdbc.sql("SELECT password_hash FROM users WHERE id=9511").query(String.class).single())).isTrue();
            assertThat(repository.notice(9511).orElseThrow().status()).isEqualTo("SENDING");
            assertThat(message.getAllRecipients()).hasSize(1);
            assertThat(message.getContent().toString()).contains("https://example.test/login","notice-student@example.test");
            return null;
        }).when(mail).send(any(MimeMessage.class));
        assertThat(worker.processOne()).isTrue();
        mvc.perform(manager(get("/v1/scholars/login-notices?scholarIds=9511"),null))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data[0].status").value("SENT"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE actor_id=9510 AND action='ISSUE_LOGIN_NOTICE'").query(Long.class).single()).isEqualTo(1);
    }
    @Test void skipsRepeatedClickButExplicitReissueGeneratesDifferentPassword() throws Exception {
        enqueue(false); worker.processOne();
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511]}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.items[0].errorCode").value("ALREADY_SENT"));
        assertThat(worker.processOne()).isFalse();
        String oldHash=jdbc.sql("SELECT password_hash FROM users WHERE id=9511").query(String.class).single();
        enqueue(true); worker.processOne();
        var messages=ArgumentCaptor.forClass(MimeMessage.class);verify(mail,times(2)).send(messages.capture());
        String password=password(messages.getAllValues().get(1));
        assertThat(encoder.matches(password,oldHash)).isFalse();
        assertThat(password(messages.getAllValues().get(0))).isNotEqualTo(password);
    }
    @Test void requiresExplicitReissueAfterDeliveryFailure() throws Exception {
        doThrow(new MailSendException("secret transport detail")).when(mail).send(any(MimeMessage.class));
        enqueue(false); worker.processOne();
        assertThat(repository.notice(9511).orElseThrow()).isEqualTo(new LoginNoticeModels.Item(9511,"FAILED","DELIVERY_FAILED"));
        doNothing().when(mail).send(any(MimeMessage.class));
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511]}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.queued").value(0))
            .andExpect(jsonPath("$.success.data.items[0].errorCode").value("REISSUE_REQUIRED"));
        assertThat(worker.processOne()).isFalse();
        enqueue(true);worker.processOne();
        assertThat(repository.notice(9511).orElseThrow().status()).isEqualTo("SENT");
    }
    @Test void rejectsDuplicateQueueAndSkipsIneligibleAccounts() throws Exception {
        enqueue(false);
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511,9512,9513,999999]}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.queued").value(0))
            .andExpect(jsonPath("$.success.data.items[0].errorCode").value("ALREADY_QUEUED"))
            .andExpect(jsonPath("$.success.data.items[1].errorCode").value("PASSWORD_ALREADY_CHANGED"))
            .andExpect(jsonPath("$.success.data.items[2].errorCode").value("ACCOUNT_DISABLED"));
    }
    @Test void rechecksEligibilityAfterQueueAndRecoversInterruptedSendingAsUnknown() throws Exception {
        enqueue(false);jdbc.sql("UPDATE users SET password_change_required=FALSE WHERE id=9511").update();
        assertThat(worker.processOne()).isTrue();verify(mail,never()).send(any(MimeMessage.class));
        assertThat(repository.notice(9511).orElseThrow().errorCode()).isEqualTo("NOT_ELIGIBLE");
        jdbc.sql("UPDATE scholar_login_notices SET status='SENDING',updated_at='2026-01-01 00:00:00' WHERE scholar_id=9511").update();
        repository.expireInterrupted();
        assertThat(repository.notice(9511).orElseThrow().errorCode()).isEqualTo("DELIVERY_UNKNOWN");
    }
    @Test void updatesSharedSenderAndRejectsBadAddressAndHeaderInjection() throws Exception {
        mvc.perform(manager(put("/v1/scholars/login-notices/sender"),"{\"fromAddress\":\"new@example.test\",\"senderName\":\"새 재단\"}"))
            .andExpect(status().isOk());
        mvc.perform(manager(get("/v1/scholars/login-notices/sender"),null))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.fromAddress").value("new@example.test"));
        mvc.perform(manager(put("/v1/scholars/login-notices/sender"),"{\"fromAddress\":\"bad\",\"senderName\":\"이름\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(manager(put("/v1/scholars/login-notices/sender"),"{\"fromAddress\":\"new@example.test\",\"senderName\":\"bad\\r\\nBcc: other@example.test\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void missingSenderRejectsBeforeQueueAndPasswordChange() throws Exception {
        jdbc.sql("DELETE FROM login_notice_settings").update();
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511]}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MAIL_UNAVAILABLE"));
        assertThat(repository.notice(9511)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"scholarIds\":[]}","{\"scholarIds\":[null]}","{\"scholarIds\":[0]}","{\"scholarIds\":[9511,9511]}"})
    void validatesTargets(String body) throws Exception {
        mvc.perform(manager(post("/v1/scholars/login-notices"),body)).andExpect(status().isBadRequest());
        assertThat(repository.notice(9511)).isEmpty();
    }
    @Test void allEndpointsRequireManager() throws Exception {
        for(String method:new String[]{"GET","POST","PUT"}) {
            String path=method.equals("PUT")?"/v1/scholars/login-notices/sender":"/v1/scholars/login-notices?scholarIds=9511";
            var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path);
            mvc.perform(request.contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
            mvc.perform(request.contentType("application/json").content("{}").with(jwt().jwt(j->j.subject("9511")).authorities(new SimpleGrantedAuthority("ROLE_SCHOLAR"))))
                .andExpect(status().isForbidden());
        }
    }
    @Test void twoWorkersCannotSendTheSameNoticeTwice() throws Exception {
        enqueue(false);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->worker.processOne());
            var two=executor.submit(()->worker.processOne());
            int processed=(one.get(5,java.util.concurrent.TimeUnit.SECONDS)?1:0)+(two.get(5,java.util.concurrent.TimeUnit.SECONDS)?1:0);
            assertThat(processed).isEqualTo(1);
        }
        verify(mail,times(1)).send(any(MimeMessage.class));
    }
    @Test void rejects501TargetsAndAccepts500() throws Exception {
        var ids=java.util.stream.LongStream.range(100000,100500).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":["+ids+"]}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.items.length()").value(500));
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":["+ids+",100501]}"))
            .andExpect(status().isBadRequest());
    }

    @Test void explicitReissueResetsForgottenPasswordAndRequiresChangeAgain() throws Exception {
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9512],\"reissue\":true}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.queued").value(1));
        assertThat(worker.processOne()).isTrue();
        var message=ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(message.capture());
        var user=jdbc.sql("SELECT password_hash,password_change_required FROM users WHERE id=9512").query().singleRow();
        assertThat(encoder.matches(password(message.getValue()),(String)user.get("password_hash"))).isTrue();
        assertThat(user.get("password_change_required")).isEqualTo(true);
    }

    @Test void reissueSignsOutExistingSessionsOfThatScholarOnly() throws Exception {
        // 9512는 폰·노트북에 로그인해 둔 상태, 9511은 다른 장학생
        jdbc.sql("""
            INSERT INTO refresh_tokens(user_id,token_hash,expires_at,created_at) VALUES
                (9512,'notice-phone',DATEADD('DAY',14,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP),
                (9512,'notice-laptop',DATEADD('DAY',14,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP),
                (9511,'notice-other',DATEADD('DAY',14,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)
            """).update();
        java.time.Instant before=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9512],\"reissue\":true}"))
            .andExpect(status().isAccepted());
        assertThat(worker.processOne()).isTrue();

        // refresh token은 그 장학생 것만 지워지고, 이 시각 전에 발급된 access token은 거절된다
        assertThat(jdbc.sql("SELECT token_hash FROM refresh_tokens WHERE user_id IN (9511,9512)").query(String.class).list())
            .containsExactly("notice-other");
        var validAfter=jdbc.sql("SELECT tokens_valid_after FROM users WHERE id=9512").query(java.time.LocalDateTime.class).single();
        assertThat(kr.co.ktscholarship.backend.common.util.UtcDateTimes.fromDb(validAfter)).isAfterOrEqualTo(before);
        assertThat(jdbc.sql("SELECT tokens_valid_after FROM users WHERE id=9511").query(java.time.LocalDateTime.class).optional()).isEmpty();
    }

    @Test void waitingRequestReadsTheNoticeCommittedAfterItsSenderLookup() throws Exception {
        var lookedUp=new java.util.concurrent.CountDownLatch(1);
        var resume=new java.util.concurrent.CountDownLatch(1);
        var pause=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            if (pause.compareAndSet(true,false)) {
                lookedUp.countDown();
                assertThat(resume.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(repository).sender();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var waiting=executor.submit(()->service.enqueue(new LoginNoticeModels.SendRequest(java.util.List.of(9511L),false),9510));
            try {
                assertThat(lookedUp.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                enqueue(false);
                worker.processOne();
            } finally {
                resume.countDown();
            }
            var result=waiting.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.queued()).isZero();
            assertThat(result.items().getFirst().errorCode()).isEqualTo("ALREADY_SENT");
        }
        verify(mail,times(1)).send(any(MimeMessage.class));
    }

    @Test void ineligibleNoticeDoesNotStopTheBatch() throws Exception {
        service.enqueue(new LoginNoticeModels.SendRequest(java.util.List.of(9511L,9512L),true),9510);
        jdbc.sql("UPDATE users SET status='INACTIVE' WHERE id=9511").update();
        worker.process();
        assertThat(repository.notice(9511).orElseThrow().errorCode()).isEqualTo("NOT_ELIGIBLE");
        assertThat(repository.notice(9512).orElseThrow().status()).isEqualTo("SENT");
        verify(mail,times(1)).send(any(MimeMessage.class));
    }

    @Test void failureBeforePasswordIssueCanBeRetriedNormally() throws Exception {
        enqueue(false);
        jdbc.sql("UPDATE users SET status='INACTIVE' WHERE id=9511").update();
        worker.processOne();
        jdbc.sql("UPDATE users SET status='ACTIVE' WHERE id=9511").update();
        enqueue(false);
        worker.processOne();
        assertThat(repository.notice(9511).orElseThrow().status()).isEqualTo("SENT");
    }

    @ParameterizedTest @ValueSource(strings={"PENDING","SENDING"})
    void lateCompletionCannotOverwriteANewAttempt(String newerState) throws Exception {
        enqueue(false);
        doAnswer(invocation->{
            jdbc.sql("UPDATE scholar_login_notices SET updated_at='2026-01-01 00:00:00' WHERE scholar_id=9511").update();
            repository.expireInterrupted();
            var rejected=service.enqueue(new LoginNoticeModels.SendRequest(java.util.List.of(9511L),false),9510);
            assertThat(rejected.items().getFirst().errorCode()).isEqualTo("REISSUE_REQUIRED");
            enqueue(true);
            if (newerState.equals("SENDING")) repository.startDelivery(9511,"new-attempt");
            return null;
        }).when(mail).send(any(MimeMessage.class));
        worker.processOne();
        assertThat(repository.notice(9511).orElseThrow().status()).isEqualTo(newerState);
    }

    @ParameterizedTest @ValueSource(strings={"2020-01-01T00:00:00","NULL"})
    void expiredLockAllowsExplicitReissueWithoutALoginFirst(String until) throws Exception {
        jdbc.sql("UPDATE users SET status='LOCKED',locked_until=:until WHERE id=9511")
            .param("until",until.equals("NULL")?null:java.time.LocalDateTime.parse(until)).update();
        enqueue(true);
        assertThat(worker.processOne()).isTrue();
        assertThat(repository.notice(9511).orElseThrow().status()).isEqualTo("SENT");
        var sent=ArgumentCaptor.forClass(MimeMessage.class);verify(mail).send(sent.capture());
        mvc.perform(post("/v1/auth/login").contentType("application/json")
            .content("{\"email\":\"notice-student@example.test\",\"password\":\""+password(sent.getValue())+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success.data.passwordChangeRequired").value(true));
    }

    @ParameterizedTest @ValueSource(strings={"LOCKED","INACTIVE"})
    void activeLockAndInactiveAccountsStillCannotReceiveNotice(String state) throws Exception {
        jdbc.sql("UPDATE users SET status=:status,locked_until='2099-01-01' WHERE id=9511").param("status",state).update();
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511],\"reissue\":true}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.queued").value(0))
            .andExpect(jsonPath("$.success.data.items[0].errorCode").value("ACCOUNT_DISABLED"));
        assertThat(worker.processOne()).isFalse();
        verify(mail,never()).send(any(MimeMessage.class));
        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE id=9511").query(String.class).single()).isEqualTo("old");
    }

    private void enqueue(boolean reissue) throws Exception {
        mvc.perform(manager(post("/v1/scholars/login-notices"),"{\"scholarIds\":[9511],\"reissue\":"+reissue+"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.success.data.queued").value(1));
    }
    private MockHttpServletRequestBuilder manager(MockHttpServletRequestBuilder request,String body) {
        request.with(jwt().jwt(j->j.subject("9510")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));
        if(body!=null) request.contentType("application/json").content(body);
        return request;
    }
    private String password(MimeMessage message) throws Exception {
        return message.getContent().toString().lines().filter(s->s.startsWith("임시 비밀번호: ")).findFirst().orElseThrow().substring("임시 비밀번호: ".length());
    }
}

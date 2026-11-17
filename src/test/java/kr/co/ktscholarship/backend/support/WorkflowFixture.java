package kr.co.ktscholarship.backend.support;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 후속 제출 API 테스트에서 사용하는 독립된 가상 데이터. */
public class WorkflowFixture {
    private final JdbcClient jdbc;
    private final boolean h2;
    public WorkflowFixture(JdbcClient jdbc,DataSource source) throws Exception {
        this.jdbc=jdbc;
        try(var connection=source.getConnection()) { h2=connection.getMetaData().getDatabaseProductName().equals("H2"); }
    }
    public void sql(String sql) { jdbc.sql(h2?sql:sql.replace(" FORMAT JSON","")).update(); }
    public void seed() {
        sql("INSERT INTO programs(id,name) VALUES(70001,'업무 검증')");
        sql("INSERT INTO cohorts(id,program_id,name) VALUES(70001,70001,'검증 기수')");
        sql("""
            INSERT INTO users(id,name,email,password_hash,role) VALUES
            (70001,'담당자','flow-manager@example.test','test','MANAGER'),
            (70002,'홍길동','flow-scholar@example.test','test','SCHOLAR'),
            (70003,'다른학생','flow-other@example.test','test','SCHOLAR')
            """);
        sql("INSERT INTO scholar_profiles(user_id,cohort_id,school_name,phone,major,student_no) VALUES(70002,70001,'학교A','010-1234-5678','전공','00123'),(70003,70001,'학교B','010-9876-5432','전공','00456')");
        sql("INSERT INTO request_templates(id,code,name,default_fields) VALUES(70001,'WORKFLOW_TEST','검증','[]' FORMAT JSON)");
        sql("""
            INSERT INTO requests(id,program_id,cohort_id,template_id,title,academic_year,semester,closes_at,status,created_by,published_at,completion_message) VALUES
            (71001,70001,70001,70001,'성적 제출',2026,'2','2099-01-01','IN_PROGRESS',70001,'2026-01-01','제출 감사합니다'),
            (71002,70001,70001,70001,'이전 제출',2026,'1','2099-01-02','IN_PROGRESS',70001,'2026-01-01',NULL),
            (71003,70001,70001,70001,'미공개',2026,'2','2099-01-01','DRAFT',70001,NULL,NULL),
            (71004,70001,70001,70001,'종료',2026,'2','2099-01-01','CLOSED',70001,'2026-01-01',NULL),
            (71005,70001,NULL,70001,'대상 없음',2026,'2','2099-01-01','IN_PROGRESS',70001,'2026-01-01',NULL)
            """);
        sql("INSERT INTO request_targets(request_id,scholar_id) VALUES(71001,70002),(71001,70003),(71002,70002),(71002,70003),(71003,70002),(71004,70002)");
        for(long request:new long[]{71001,71002}) sql("""
            INSERT INTO request_fields(request_id,field_key,label,field_type,required,validation,sort_order) VALUES
            (%d,'name','이름','TEXT',TRUE,NULL,0),
            (%d,'gpa','평점','NUMBER',TRUE,'{"min":0,"max":4.5}' FORMAT JSON,1),
            (%d,'receipt','증빙','FILE',TRUE,'{"allowedExtensions":["pdf"],"maxSizeMb":1,"maxFiles":2}' FORMAT JSON,2),
            (%d,'other_file','보완 파일','FILE',FALSE,'{"allowedExtensions":["pdf"],"maxSizeMb":1,"maxFiles":2}' FORMAT JSON,3)
            """.formatted(request,request,request,request));
        sql("""
            INSERT INTO submissions(id,request_id,scholar_id,status,answers,version,submitted_at) VALUES
            (72001,71001,70002,'DRAFT','{"name":"홍길동","gpa":3.5}' FORMAT JSON,2,NULL),
            (72002,71001,70003,'DRAFT','{}' FORMAT JSON,0,NULL),
            (72003,71002,70002,'SUBMITTED','{"name":"홍길동","gpa":3.0}' FORMAT JSON,1,'2026-11-01')
            """);
        sql("""
            INSERT INTO submission_files(id,submission_id,field_key,s3_key,original_name,content_type,size_bytes,upload_status) VALUES
            (73001,72001,'receipt','workflow/receipt','성적.pdf','application/pdf',123,'PENDING'),
            (73002,72002,'receipt','workflow/other','타인.pdf','application/pdf',123,'UPLOADED'),
            (73003,72001,'receipt','workflow/deleted','삭제.pdf','application/pdf',123,'DELETED')
            """);
    }
    public void clean() {
        sql("DELETE FROM audit_logs WHERE actor_id BETWEEN 70001 AND 70003");
        sql("DELETE FROM submission_histories WHERE submission_id IN (SELECT id FROM submissions WHERE request_id BETWEEN 71001 AND 71005)");
        sql("DELETE FROM revision_request_fields WHERE revision_request_id IN (SELECT id FROM revision_requests WHERE submission_id IN (SELECT id FROM submissions WHERE request_id BETWEEN 71001 AND 71005))");
        sql("DELETE FROM revision_requests WHERE submission_id IN (SELECT id FROM submissions WHERE request_id BETWEEN 71001 AND 71005)");
        sql("DELETE FROM submission_files WHERE submission_id IN (SELECT id FROM submissions WHERE request_id BETWEEN 71001 AND 71005)");
        sql("DELETE FROM submissions WHERE request_id BETWEEN 71001 AND 71005");
        sql("DELETE FROM request_fields WHERE request_id BETWEEN 71001 AND 71005");
        sql("DELETE FROM request_targets WHERE request_id BETWEEN 71001 AND 71005");
        sql("DELETE FROM requests WHERE id BETWEEN 71001 AND 71005");
        sql("DELETE FROM request_templates WHERE id=70001");
        sql("DELETE FROM scholar_profiles WHERE cohort_id=70001");
        sql("DELETE FROM cohorts WHERE id=70001");
        sql("DELETE FROM users WHERE id BETWEEN 70001 AND 70003");
        sql("DELETE FROM programs WHERE id=70001");
    }
}

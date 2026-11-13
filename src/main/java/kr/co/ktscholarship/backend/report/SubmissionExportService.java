package kr.co.ktscholarship.backend.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import kr.co.ktscholarship.backend.common.util.PhoneMasker;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionExportService {
    private static final Map<String,String> FIXED=Map.ofEntries(
        Map.entry("name","이름"),Map.entry("cohort","기수"),Map.entry("email","이메일"),Map.entry("university","학교"),
        Map.entry("major","전공"),Map.entry("studentNo","학번"),Map.entry("phone","연락처"),Map.entry("status","상태"),
        Map.entry("submittedAt","제출 일시"),Map.entry("revisionReason","보완 사유"),Map.entry("gpa","평점"),Map.entry("gpaScale","만점 기준"),Map.entry("credits","이수 학점"));
    private static final Set<String> STATES=Set.of("NOT_SUBMITTED","WAITING","DRAFT","SUBMITTED","RESUBMITTED","REVISION_REQUIRED","CONFIRMED");
    private final SubmissionExportRepository repository;
    public SubmissionExportService(SubmissionExportRepository repository) { this.repository=repository; }
    @Transactional
    public Export create(long actor,long request,String format,String statuses,String columns,String ip) {
        if(!Set.of("xlsx","csv").contains(format)) throw invalid();
        Set<String> filters=split(statuses);
        if(!STATES.containsAll(filters)) throw invalid();
        if(!repository.exists(request)) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        var fields=repository.fields(request);
        var labels=new LinkedHashMap<>(FIXED);
        fields.forEach(f->labels.putIfAbsent(f.key(),f.label()));
        var selected=new LinkedHashSet<String>(); selected.add("name");
        if(columns==null) {
            selected.addAll(List.of("cohort","email","status","submittedAt"));
            fields.forEach(f->selected.add(f.key()));
        } else selected.addAll(split(columns));
        if(!labels.keySet().containsAll(selected)) throw invalid();
        var rows=repository.rows(request).stream().filter(r->matches(r.status(),filters)).toList();
        var files=new HashMap<Long,Map<String,List<String>>>();
        for(var f:repository.files(request)) files.computeIfAbsent(f.submissionId(),k->new HashMap<>())
            .computeIfAbsent(f.field(),k->new ArrayList<>()).add(f.name());
        var reasons=new HashMap<Long,String>();
        for(var r:repository.revisions(request)) reasons.putIfAbsent(r.submissionId(),r.reason());
        var types=new HashMap<String,String>();fields.forEach(f->types.put(f.key(),f.type()));
        List<List<Object>> values=new ArrayList<>();
        values.add(selected.stream().map(k->(Object)labels.get(k)).toList());
        for(var row:rows) values.add(selected.stream().map(key->value(row,key,types,files,reasons)).toList());
        byte[] bytes=format.equals("xlsx")?xlsx(values):csv(values);
        repository.audit(actor,request,rows.size(),ip,Instant.now());
        return new Export(bytes,"request_"+request+"."+format,format.equals("xlsx")?
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv;charset=UTF-8");
    }
    private Set<String> split(String value) {
        var result=new LinkedHashSet<String>();
        if(value==null) return result;
        for(String part:value.split(",",-1)) { if(part.isBlank()) throw invalid();result.add(part.trim()); }
        return result;
    }
    private boolean matches(String status,Set<String> filters) {
        return filters.isEmpty() || filters.contains(status==null?"NOT_SUBMITTED":status)
            || filters.contains("NOT_SUBMITTED") && (status==null||status.equals("DRAFT"))
            || filters.contains("WAITING") && Set.of("SUBMITTED","RESUBMITTED").contains(status==null?"":status);
    }
    private Object value(SubmissionExportRepository.Row row,String key,Map<String,String> types,
        Map<Long,Map<String,List<String>>> files,Map<Long,String> reasons) {
        Object value=switch(key) {
            case "name"->row.name();case "cohort"->row.cohort();case "email"->row.email();case "university"->row.university();
            case "major"->row.major();case "studentNo"->row.studentNo();case "phone"->PhoneMasker.mask(row.phone());
            case "status"->switch(row.status()==null?"DRAFT":row.status()) {
                case "DRAFT"->"미제출";case "SUBMITTED"->"검토 대기";case "RESUBMITTED"->"재제출";
                case "REVISION_REQUIRED"->"보완 필요";case "CONFIRMED"->"확인 완료";default->row.status();};
            case "submittedAt"->row.submittedAt()==null?null:row.submittedAt().toString();
            case "revisionReason"->row.submissionId()==null?null:reasons.get(row.submissionId());
            default->"FILE".equals(types.get(key))?String.join(", ",files.getOrDefault(row.submissionId(),Map.of()).getOrDefault(key,List.of())):row.answers().get(key);
        };
        if(value==null) return "";
        if("BANK_ACCOUNT".equals(types.get(key))) {
            String text=value.toString();return "****"+text.substring(Math.max(0,text.length()-4));
        }
        if(value instanceof Collection<?> items) return String.join(", ",items.stream().map(String::valueOf).toList());
        return value;
    }
    private byte[] xlsx(List<List<Object>> values) {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("제출 현황");
            for(int r=0;r<values.size();r++) {
                var row=sheet.createRow(r);var cells=values.get(r);
                for(int c=0;c<cells.size();c++) {
                    var cell=row.createCell(c);Object value=cells.get(c);
                    if(value instanceof Number n) cell.setCellValue(n.doubleValue());
                    else if(value instanceof Boolean b) cell.setCellValue(b);
                    else { String text=value.toString();if(text.length()>32767) throw invalid();cell.setCellValue(text); }
                }
            }
            sheet.createFreezePane(0,1);
            for(int c=0;c<values.getFirst().size();c++) sheet.setColumnWidth(c,24*256);
            workbook.write(output);return output.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("엑셀 파일 생성 실패",e); }
    }
    private byte[] csv(List<List<Object>> values) {
        StringBuilder out=new StringBuilder("\ufeff");
        for(var row:values) {
            var cells=new ArrayList<String>();
            for(Object value:row) {
                String text=value.toString();String leading=text.stripLeading();
                if(!(value instanceof Number) && (!text.isEmpty() && (text.charAt(0)=='\t'||text.charAt(0)=='\r'||text.charAt(0)=='\n')
                    || !leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0)) text="'"+text;
                cells.add("\""+text.replace("\"","\"\"")+"\"");
            }
            out.append(String.join(",",cells)).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private BusinessException invalid(){return new BusinessException(ErrorCode.INVALID_REQUEST);}
    public record Export(byte[] bytes,String filename,String contentType) {}
}

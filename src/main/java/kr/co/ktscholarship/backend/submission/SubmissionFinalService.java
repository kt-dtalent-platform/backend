package kr.co.ktscholarship.backend.submission;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmissionFinalService {
    private final SubmissionDraftRepository drafts;
    private final SubmissionFinalRepository repository;
    private final SubmissionDraftValidator validator;
    public SubmissionFinalService(SubmissionDraftRepository drafts,SubmissionFinalRepository repository,SubmissionDraftValidator validator) {
        this.drafts=drafts; this.repository=repository; this.validator=validator;
    }

    @Transactional
    public Result submit(long user,long requestId,SubmissionDraftRequest body,boolean resubmit) {
        boolean target=drafts.lockTarget(requestId,user);
        var previous=repository.lock(requestId,user).orElse(null);
        var request=repository.request(requestId).orElseThrow(()->new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        if(!target) throw new BusinessException(ErrorCode.NOT_A_TARGET);
        Instant now=Instant.now();
        if(!request.status().equals("IN_PROGRESS") || !now.isBefore(request.closesAt())) throw new BusinessException(ErrorCode.DEADLINE_PASSED);
        if(resubmit && (previous==null || !previous.status().equals("REVISION_REQUIRED"))) throw new BusinessException(ErrorCode.NOT_IN_REVISION);
        if(!resubmit && previous!=null && !previous.status().equals("DRAFT")) throw new BusinessException(ErrorCode.ALREADY_SUBMITTED);
        int version=previous==null?0:previous.version();
        if(version!=body.version() || version==Integer.MAX_VALUE) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        var answers=new LinkedHashMap<>(body.answers());
        SubmissionFinalRepository.Revision revision=null;
        if(resubmit) {
            revision=repository.revision(previous.id()).orElseThrow(()->new BusinessException(ErrorCode.NOT_IN_REVISION));
            if(revision.keys().isEmpty()) throw new BusinessException(ErrorCode.NOT_IN_REVISION);
            if(!revision.keys().containsAll(answers.keySet())) throw new BusinessException(ErrorCode.FIELD_NOT_EDITABLE);
            var merged=new LinkedHashMap<>(previous.answers()); merged.putAll(answers); answers=merged;
        }
        var files=previous==null?List.<SubmissionFinalRepository.FileSnapshot>of():repository.files(previous.id());
        validator.validateComplete(answers,drafts.fields(requestId),files);
        now=now.truncatedTo(ChronoUnit.SECONDS);
        String state=resubmit?"RESUBMITTED":"SUBMITTED";
        long id;
        if(previous==null) id=repository.insert(requestId,user,answers,now);
        else {
            if(repository.update(previous,answers,state,now)!=1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            id=previous.id();
        }
        repository.history(id,previous==null?null:previous.status(),state,user,previous==null?Map.of():previous.answers(),files,now);
        if(revision!=null) repository.resolve(revision.id(),now);
        Instant first=previous==null||previous.submittedAt()==null?now:previous.submittedAt();
        return new Result(id,state,first,resubmit?now:null,request.completionMessage(),version+1);
    }
    public record Result(long submissionId,String status,Instant submittedAt,Instant resubmittedAt,String completionMessage,int version) {}
}

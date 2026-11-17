package kr.co.ktscholarship.backend.submission;

import java.time.Instant;

public record SubmissionDraftResponse(long submissionId, String status, Instant savedAt, int version) {}

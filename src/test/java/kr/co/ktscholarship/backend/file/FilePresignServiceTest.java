package kr.co.ktscholarship.backend.file;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.ktscholarship.backend.common.exception.BusinessException;
import kr.co.ktscholarship.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class FilePresignServiceTest {
    @Test
    void unconfiguredStorageDoesNotCreatePendingFile() {
        var repository = mock(FilePresignRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<S3Presigner> unavailable = mock(ObjectProvider.class);
        when(repository.lockSubmission(1)).thenReturn(Optional.of(new FilePresignRepository.Submission(1,2,3,"DRAFT")));
        when(repository.requestState(2,3)).thenReturn(new FilePresignRepository.RequestState(
            "IN_PROGRESS",Instant.parse("2099-01-01T00:00:00Z"),true,true));
        when(repository.findField(2,"receipt")).thenReturn(Optional.of(new FilePresignRepository.Field(
            "receipt","FILE",Map.of("allowedExtensions",List.of("pdf"),"maxSizeMb",1,"maxFiles",1))));
        var service = new FilePresignService(repository,unavailable,new S3UploadProperties(false,"",""));

        assertThatThrownBy(()->service.presign(3,1,new FilePresignRequest("receipt","a.pdf","application/pdf",1L)))
            .isInstanceOfSatisfying(BusinessException.class,e->org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
        verify(repository,never()).insertPending(anyLong(),any(),anyString(),any());
    }
}

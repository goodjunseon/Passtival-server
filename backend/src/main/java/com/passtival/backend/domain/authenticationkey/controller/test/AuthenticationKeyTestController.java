package com.passtival.backend.domain.authenticationkey.controller.test;

import java.io.IOException;

import org.apache.poi.ooxml.POIXMLException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.passtival.backend.domain.authenticationkey.repository.AuthenticationKeyRepository;
import com.passtival.backend.domain.authenticationkey.service.AuthenticationKeyImportService;
import com.passtival.backend.domain.authenticationkey.service.AuthenticationKeyImportService.ImportSummary;
import com.passtival.backend.global.common.BaseResponse;
import com.passtival.backend.global.common.BaseResponseStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/test/authentication-keys")
@RequiredArgsConstructor
@Tag(name = "Test-API", description = "[백엔드 용] 테스트용 인증키 API")
public class AuthenticationKeyTestController {

  private final AuthenticationKeyImportService authenticationKeyImportService;
  private final AuthenticationKeyRepository authenticationKeyRepository;

  @Operation(
      summary = "업로드한 인증키 엑셀 import",
      description = "xlsx 파일의 첫 시트 A열에서 5자리 인증키를 저장합니다. "
          + "rowsToImport를 생략하면 전체 행을, 지정하면 앞에서부터 해당 행 수만큼 읽습니다."
  )
  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BaseResponse<ImportResult> importAuthenticationKeys(
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "rowsToImport", required = false) Integer rowsToImport
  ) {
    try {
      long startNanos = System.nanoTime();
      ImportSummary summary = authenticationKeyImportService.importXlsx(file, rowsToImport);
      // 서비스의 트랜잭션 커밋까지 포함하며, HTTP 파일 전송 시간은 제외합니다.
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

      return BaseResponse.success(
          ImportResult.builder()
              .requestedRows(rowsToImport)
              .readRows(summary.readRows())
              .insertedCount(summary.insertedCount())
              .skippedRows(summary.readRows() - summary.insertedCount())
              .parseElapsedMs(summary.parseElapsedMs())
              .saveElapsedMs(summary.saveElapsedMs())
              .elapsedMs(elapsedMs)
              .build()
      );
    } catch (IllegalArgumentException | IOException | POIXMLException e) {
      return BaseResponse.fail(BaseResponseStatus.BAD_REQUEST,
          "파일과 행 수를 확인해주세요. 첫 시트 A열에 인증키가 있는 xlsx 파일이 필요합니다.");
    } catch (Exception e) {
      return BaseResponse.fail(BaseResponseStatus.INTERNAL_SERVER_ERROR,
          "인증키 import 실패: " + e.getMessage());
    }
  }

  @Operation(
      summary = "전체 인증키 삭제",
      description = "테스트를 위해 authentication_key 테이블의 전체 데이터를 삭제합니다."
  )
  @DeleteMapping("/all")
  public BaseResponse<DeleteResult> deleteAllAuthenticationKeys() {
    try {
      long beforeCount = authenticationKeyRepository.count();
      long startNanos = System.nanoTime();

      authenticationKeyRepository.deleteAllInBatch();

      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      long afterCount = authenticationKeyRepository.count();

      return BaseResponse.success(
          DeleteResult.builder()
              .beforeCount(beforeCount)
              .afterCount(afterCount)
              .deletedCount(beforeCount - afterCount)
              .elapsedMs(elapsedMs)
              .build()
      );
    } catch (Exception e) {
      return BaseResponse.fail(BaseResponseStatus.INTERNAL_SERVER_ERROR,
          "인증키 전체 삭제 실패: " + e.getMessage());
    }
  }

  @Getter
  @Builder
  public static class ImportResult {

    private Integer requestedRows;
    private int readRows;
    private int insertedCount;
    private int skippedRows;
    private long parseElapsedMs;
    private long saveElapsedMs;
    private long elapsedMs;
  }

  @Getter
  @Builder
  public static class DeleteResult {

    private long beforeCount;
    private long afterCount;
    private long deletedCount;
    private long elapsedMs;
  }
}

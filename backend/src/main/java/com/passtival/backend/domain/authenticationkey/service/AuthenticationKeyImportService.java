package com.passtival.backend.domain.authenticationkey.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.passtival.backend.domain.authenticationkey.model.AuthenticationKey;
import com.passtival.backend.domain.authenticationkey.repository.AuthenticationKeyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationKeyImportService {

  private final AuthenticationKeyRepository authenticationKeyRepository;

  /**
   * 업로드한 엑셀의 첫 시트 A열을 읽습니다. requestedRows가 없으면 전체 행을 처리합니다.
   */
  @Transactional(rollbackFor = IOException.class)
  public ImportSummary importXlsx(MultipartFile file, Integer requestedRows) throws IOException {
    if (file.isEmpty()) {
      throw new IllegalArgumentException("비어 있는 파일은 가져올 수 없습니다.");
    }
    if (requestedRows != null && requestedRows < 1) {
      throw new IllegalArgumentException("요청 row 수는 1 이상이어야 합니다.");
    }

    long parseStartNanos = System.nanoTime();
    try (InputStream inputStream = file.getInputStream();
        Workbook workbook = new XSSFWorkbook(inputStream)) {
      if (workbook.getNumberOfSheets() == 0) {
        throw new IllegalArgumentException("엑셀에 시트가 없습니다.");
      }

      Sheet sheet = workbook.getSheetAt(0);
      int sourceRows = sheet.getLastRowNum() + 1;
      int readRows = requestedRows == null ? sourceRows : Math.min(sourceRows, requestedRows);
      List<AuthenticationKey> entities = new ArrayList<>();

      for (int rowIndex = 0; rowIndex < readRows; rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(0);
        if (cell == null) {
          continue;
        }

        cell.setCellType(CellType.STRING);
        String value = cell.getStringCellValue();
        if (value == null) {
          continue;
        }

        String key = value.trim();
        if (key.length() == 5) {
          entities.add(new AuthenticationKey(key, null));
        }
      }

      long parseElapsedMs = elapsedMillis(parseStartNanos);
      log.info("인증키 파싱 완료 - readRows: {}, validKeys: {}, skippedRows: {}, parseElapsedMs: {}",
          readRows, entities.size(), readRows - entities.size(), parseElapsedMs);

      long saveStartNanos = System.nanoTime();
      authenticationKeyRepository.saveAll(entities); // 전체 저장
      authenticationKeyRepository.flush();
      long saveElapsedMs = elapsedMillis(saveStartNanos);
      log.info("인증키 DB 저장 및 flush 완료 - insertCount: {}, saveElapsedMs: {}",
          entities.size(), saveElapsedMs);

      return new ImportSummary(readRows, entities.size(), parseElapsedMs, saveElapsedMs);
    }
  }

  private long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  public record ImportSummary(int readRows, int insertedCount, long parseElapsedMs,
                              long saveElapsedMs) {

  }
}

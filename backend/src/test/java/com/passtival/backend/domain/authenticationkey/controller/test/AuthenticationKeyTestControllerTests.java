package com.passtival.backend.domain.authenticationkey.controller.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.passtival.backend.domain.authenticationkey.model.AuthenticationKey;
import com.passtival.backend.domain.authenticationkey.repository.AuthenticationKeyRepository;
import com.passtival.backend.domain.authenticationkey.service.AuthenticationKeyImportService;
import com.passtival.backend.global.discord.DiscordService;
import com.passtival.backend.global.handler.GlobalExceptionHandler;

class AuthenticationKeyTestControllerTests {

  private static final String IMPORT_URL = "/api/test/authentication-keys/import";
  private static final String XLSX_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private AuthenticationKeyRepository repository;
  private DiscordService discordService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    repository = mock(AuthenticationKeyRepository.class);
    discordService = mock(DiscordService.class);
    AuthenticationKeyImportService service = new AuthenticationKeyImportService(repository);
    mockMvc = MockMvcBuilders.standaloneSetup(
            new AuthenticationKeyTestController(service, repository))
        .setControllerAdvice(new GlobalExceptionHandler(discordService))
        .build();
  }

  @Test
  void importsOnlyValidKeysFromFirstSheetColumnA() throws Exception {
    MockMultipartFile file = xlsx(workbook -> {
      Sheet sheet = workbook.createSheet();
      sheet.createRow(0).createCell(0).setCellValue(" 00123 ");
      sheet.createRow(1).createCell(0).setCellValue("1234");
      // 2행 인덱스는 빈 행, 3행 인덱스는 A열이 없는 행입니다.
      sheet.createRow(3).createCell(1).setCellValue("99999");
      sheet.createRow(4).createCell(0).setCellValue("ABCDE");
      workbook.createSheet().createRow(0).createCell(0).setCellValue("XXXXX");
    });

    mockMvc.perform(multipart(IMPORT_URL).file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.requestedRows").value(nullValue()))
        .andExpect(jsonPath("$.result.readRows").value(5))
        .andExpect(jsonPath("$.result.insertedCount").value(2))
        .andExpect(jsonPath("$.result.skippedRows").value(3))
        .andExpect(jsonPath("$.result.parseElapsedMs").isNumber())
        .andExpect(jsonPath("$.result.saveElapsedMs").isNumber())
        .andExpect(jsonPath("$.result.elapsedMs").isNumber());

    assertThat(savedKeys()).extracting(AuthenticationKey::getAuthenticationKey)
        .containsExactly("00123", "ABCDE");
    verify(repository).flush();
  }

  @Test
  void importsAllRowsWithoutThePreviousTenThousandRowLimit() throws Exception {
    MockMultipartFile file = xlsx(workbook -> {
      Sheet sheet = workbook.createSheet();
      for (int index = 0; index < 10_001; index++) {
        sheet.createRow(index).createCell(0).setCellValue(String.format("%05d", index));
      }
    });

    mockMvc.perform(multipart(IMPORT_URL).file(file))
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.readRows").value(10_001))
        .andExpect(jsonPath("$.result.insertedCount").value(10_001));

    assertThat(savedKeys()).hasSize(10_001);
  }

  @Test
  void rowLimitCountsSkippedRowsAsWellAsSavedKeys() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL).file(keys("12345", "bad", "67890"))
            .param("rowsToImport", "2"))
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.requestedRows").value(2))
        .andExpect(jsonPath("$.result.readRows").value(2))
        .andExpect(jsonPath("$.result.insertedCount").value(1))
        .andExpect(jsonPath("$.result.skippedRows").value(1));

    assertThat(savedKeys()).extracting(AuthenticationKey::getAuthenticationKey)
        .containsExactly("12345");
  }

  @Test
  void limitsReadingToAvailableRows() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL).file(keys("12345")).param("rowsToImport", "100"))
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.readRows").value(1))
        .andExpect(jsonPath("$.result.insertedCount").value(1));
  }

  @Test
  void emptySheetReturnsZeroCounts() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL).file(keys()))
        .andExpect(jsonPath("$.isSuccess").value(true))
        .andExpect(jsonPath("$.result.readRows").value(0))
        .andExpect(jsonPath("$.result.insertedCount").value(0))
        .andExpect(jsonPath("$.result.skippedRows").value(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "not-a-number"})
  void rejectsInvalidRowLimits(String rows) throws Exception {
    mockMvc.perform(multipart(IMPORT_URL).file(keys("12345")).param("rowsToImport", rows))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(repository, discordService);
  }

  @Test
  void rejectsMissingFile() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(repository, discordService);
  }

  @Test
  void rejectsEmptyFile() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL)
            .file(new MockMultipartFile("file", "empty.xlsx", XLSX_CONTENT_TYPE, new byte[0])))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(repository, discordService);
  }

  @Test
  void rejectsNonExcelContentEvenWithXlsxFileName() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL)
            .file(
                new MockMultipartFile("file", "invalid.xlsx", XLSX_CONTENT_TYPE, new byte[]{1, 2, 3})))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(repository, discordService);
  }

  @Test
  void rejectsWorkbookWithoutSheets() throws Exception {
    mockMvc.perform(multipart(IMPORT_URL).file(xlsx(workbook -> {
        })))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(repository, discordService);
  }

  @Test
  void reportsDatabaseFailureInsteadOfSuccessfulImport() throws Exception {
    doThrow(new DataIntegrityViolationException("insert failed")).when(repository)
        .saveAll(anyList());

    mockMvc.perform(multipart(IMPORT_URL).file(keys("12345")))
        .andExpect(jsonPath("$.isSuccess").value(false))
        .andExpect(jsonPath("$.code").value(500));
  }

  private Iterable<AuthenticationKey> savedKeys() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<AuthenticationKey>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(repository).saveAll(captor.capture());
    return captor.getValue();
  }

  private MockMultipartFile keys(String... keys) throws Exception {
    return xlsx(workbook -> {
      Sheet sheet = workbook.createSheet();
      for (int index = 0; index < keys.length; index++) {
        sheet.createRow(index).createCell(0).setCellValue(keys[index]);
      }
    });
  }

  private MockMultipartFile xlsx(Consumer<XSSFWorkbook> populate) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      populate.accept(workbook);
      workbook.write(output);
      return new MockMultipartFile("file", "keys.xlsx", XLSX_CONTENT_TYPE, output.toByteArray());
    }
  }
}

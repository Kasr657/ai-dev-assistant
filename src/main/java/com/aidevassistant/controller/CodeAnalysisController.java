package com.aidevassistant.controller;

import com.aidevassistant.dto.AnalysisRequest;
import com.aidevassistant.dto.AnalysisResponse;
import com.aidevassistant.service.CodeAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that implements the generated {@link ApiApi} interface.
 * Delegates all processing to {@link CodeAnalysisService}.
 *
 * <p>The {@code xRequestId} parameter is accepted to satisfy the generated interface
 * contract but is intentionally unused here — the {@code X-Request-Id} header is
 * already read and stored in MDC by {@link com.aidevassistant.util.RequestIdFilter}
 * before this method is invoked.
 */
@RestController
@RequiredArgsConstructor
public class CodeAnalysisController implements ApiApi {

    private final CodeAnalysisService service;

    @Override
    public ResponseEntity<AnalysisResponse> analyzeCode(AnalysisRequest analysisRequest,
                                                         String xRequestId) {
        AnalysisResponse result = service.analyze(analysisRequest);
        return ResponseEntity.ok(result);
    }
}

package com.pab.ficc.ibp.modelgate.server.common;

import com.pab.ficc.ibp.modelgate.server.domain.vo.AuditLogVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadListVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecordCsvTest {
    @Test
    void escapesCsvFieldsAndSpreadsheetFormulas() {
        var row = new AuditLogVO();
        row.setTraceId("=1+1");
        row.setErrorMsg("comma, quote\" and\nline");
        String csv = RecordCsv.audit(row);
        assertTrue(csv.contains("\"'=1+1\""));
        assertTrue(csv.contains("\"comma, quote\"\" and\nline\""));
    }

    @Test
    void marksAmbiguousPayloadTokens() {
        var row = new PayloadListVO();
        row.setAuditMatchCount(2);
        row.setInputTokens(98123);
        row.setOutputTokens(19823);
        String csv = RecordCsv.payload(row);
        assertFalse(csv.contains("98123"));
        assertFalse(csv.contains("19823"));
        assertTrue(csv.contains("\"存疑\""));
    }
}

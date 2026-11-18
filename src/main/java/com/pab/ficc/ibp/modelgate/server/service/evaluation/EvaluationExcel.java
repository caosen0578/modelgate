package com.pab.ficc.ibp.modelgate.server.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationStore.invalid;

/** Excel 只负责将业务列转换为既有样本格式，预览阶段不写入数据库。 */
@Service
public class EvaluationExcel {
    private static final List<String> HEADERS = List.of("样本标题", "问题", "参考答案", "难度", "系统提示词");
    private static final Map<String, String> DIFFICULTIES = Map.of(
            "未分级", "UNRATED", "简单", "EASY", "中等", "MEDIUM", "困难", "HARD",
            "UNRATED", "UNRATED", "EASY", "EASY", "MEDIUM", "MEDIUM", "HARD", "HARD");
    private final ObjectMapper mapper;
    private final int maxCases;

    public EvaluationExcel(ObjectMapper mapper, @Value("${modelgate.evaluation.max-cases:200}") int maxCases) {
        this.mapper = mapper;
        this.maxCases = Math.max(1, maxCases);
    }

    public List<EvaluationService.ImportCase> preview(byte[] bytes) {
        checkArchive(bytes);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("评测样本");
            if (sheet == null) throw invalid("缺少「评测样本」工作表，请使用本页下载的 Excel 模板");
            var formatter = new DataFormatter(Locale.ROOT);
            var header = sheet.getRow(0);
            if (header == null) throw invalid("第 1 行缺少表头，请使用本页下载的 Excel 模板");
            for (int col = 0; col < HEADERS.size(); col++) {
                if (!HEADERS.get(col).equals(text(header.getCell(col), formatter).trim()))
                    throw invalid("第 1 行第 " + (col + 1) + " 列应为「" + HEADERS.get(col) + "」");
            }
            checkExtraColumns(header, formatter);
            var samples = new ArrayList<EvaluationService.ImportCase>();
            for (var row : sheet) {
                if (row.getRowNum() == 0) continue;
                checkExtraColumns(row, formatter);
                var cells = new ArrayList<String>();
                for (int col = 0; col < HEADERS.size(); col++) cells.add(text(row.getCell(col), formatter));
                if (cells.stream().allMatch(String::isBlank)) continue;
                String prefix = "第 " + (row.getRowNum() + 1) + " 行：";
                if (samples.size() >= maxCases) throw invalid(prefix + "单次最多导入 " + maxCases + " 条样本");
                if (cells.get(0).length() > 128) throw invalid(prefix + "样本标题不能超过 128 字");
                if (cells.get(1).isBlank()) throw invalid(prefix + "问题不能为空");
                String level = cells.get(3).trim().toUpperCase(Locale.ROOT);
                String difficulty = level.isEmpty() ? "UNRATED" : DIFFICULTIES.get(level);
                if (difficulty == null) throw invalid(prefix + "难度须为未分级、简单、中等或困难");
                var request = mapper.createObjectNode();
                var messages = request.putArray("messages");
                if (!cells.get(4).isBlank()) messages.addObject().put("role", "system").put("content", cells.get(4));
                messages.addObject().put("role", "user").put("content", cells.get(1));
                samples.add(new EvaluationService.ImportCase(cells.get(0), null, request, cells.get(2), difficulty));
            }
            if (samples.isEmpty()) throw invalid("「评测样本」工作表没有可导入的数据，请从第 2 行开始填写");
            return samples;
        } catch (IOException | org.apache.poi.ooxml.POIXMLException | IllegalArgumentException error) {
            throw invalid("无法解析 Excel 文件，请使用本页下载的 .xlsx 模板，且不要设置密码");
        }
    }

    private static void checkExtraColumns(org.apache.poi.ss.usermodel.Row row, DataFormatter formatter) {
        for (var cell : row) {
            if (cell.getColumnIndex() >= HEADERS.size() && !text(cell, formatter).isBlank())
                throw invalid("第 " + (row.getRowNum() + 1) + " 行包含模板以外的列，请仅填写五个业务列");
        }
    }

    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR
                || cell.getCellType() == CellType.BOOLEAN)
            throw invalid("第 " + (cell.getRowIndex() + 1) + " 行第 " + (cell.getColumnIndex() + 1) + " 列请填写文本，不接受公式、错误或布尔值");
        return formatter.formatCellValue(cell);
    }

    /** 在 XML 解析前限制解压体积，防止小文件耗尽管理接口内存。 */
    private static void checkArchive(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 16 * 1024 * 1024) throw invalid("请选择非空且不超过 16 MB 的 .xlsx 文件");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0;
            long total = 0;
            byte[] chunk = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 256) throw invalid("Excel 文件内容过多，请仅保留样本及填写说明工作表");
                int count;
                while ((count = zip.read(chunk)) != -1) {
                    total += count;
                    if (total > 64L * 1024 * 1024) throw invalid("Excel 解压内容超过 64 MB，请拆分文件");
                }
            }
            if (entries == 0) throw invalid("请选择有效的 .xlsx 文件，旧版 .xls 请另存为 .xlsx");
        } catch (IOException error) {
            throw invalid("Excel 文件已损坏，请重新另存为 .xlsx 文件");
        }
    }
}

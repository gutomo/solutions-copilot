package com.example.copilot.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProposalTool {

    private static final Logger log = LoggerFactory.getLogger(ProposalTool.class);

    private final Path outputDir;

    public ProposalTool(@Value("${app.proposals.dir:${java.io.tmpdir}}") String outputDir) {
        this.outputDir = Path.of(outputDir);
    }

    public record ProposalDoc(String filePath, String fileName, long sizeBytes) {}

    @Tool(description = """
            Generate a customer-facing reseller proposal document (.docx) from
            the already-computed ROI figures. You MUST first call the reseller
            margin tool to obtain the figures, then pass them to this tool
            verbatim. This tool performs NO arithmetic -- it only renders the
            document. Do not estimate, recompute, or round the numbers; pass
            the exact values returned by the margin tool. Returns the file
            path, file name, and size in bytes; the raw .docx bytes are not
            returned and never enter your context.
            """)
    public ProposalDoc generateProposal(
            @ToolParam(description = "customer or account name, e.g. \"ABC Corp\"")
            String customerName,
            @ToolParam(description = "ISO currency code, e.g. \"JPY\", \"USD\"")
            String currency,
            @ToolParam(description = "monthly Azure consumption in currency units")
            double monthlyConsumption,
            @ToolParam(description = "reseller margin as a percent, e.g. 17.4")
            double marginPercent,
            @ToolParam(description = "contract term in whole months")
            int termMonths,
            @ToolParam(description = "monthly margin, EXACTLY as returned by the margin tool")
            double monthlyMargin,
            @ToolParam(description = "total customer cost over term, EXACTLY as returned by the margin tool")
            double totalCustomerCost,
            @ToolParam(description = "total margin over term, EXACTLY as returned by the margin tool")
            double totalMarginOverTerm,
            @ToolParam(description = "effective annualized margin, EXACTLY as returned by the margin tool")
            double effectiveAnnualizedMargin,
            @ToolParam(required = false, description = "optional short value-prop / notes paragraph (~1-3 sentences)")
            String valueProp
    ) {
        log.info("[tool:proposal] called  customerName={} currency={} monthlyConsumption={} marginPercent={} termMonths={} "
                        + "monthlyMargin={} totalCustomerCost={} totalMarginOverTerm={} effectiveAnnualizedMargin={} valueProp.len={}",
                customerName, currency, monthlyConsumption, marginPercent, termMonths,
                monthlyMargin, totalCustomerCost, totalMarginOverTerm, effectiveAnnualizedMargin,
                valueProp == null ? 0 : valueProp.length());

        // Trust-but-verify: recompute the four figures from raw inputs and
        // compare against what the model passed in. Don't hard-fail on
        // divergence -- that's a Phase 4 policy decision -- but emit a WARN so
        // a fudged number is loud in the logs.
        double recompMonthly = monthlyConsumption * marginPercent / 100.0;
        double recompCost    = monthlyConsumption * termMonths;
        double recompMargin  = recompMonthly * termMonths;
        double recompAnnual  = recompMonthly * 12;
        double eps = 0.01;
        boolean ok =
                Math.abs(round2(recompMonthly) - monthlyMargin)             <= eps
             && Math.abs(round2(recompCost)    - totalCustomerCost)         <= eps
             && Math.abs(round2(recompMargin)  - totalMarginOverTerm)       <= eps
             && Math.abs(round2(recompAnnual)  - effectiveAnnualizedMargin) <= eps;
        if (!ok) {
            log.warn("[tool:proposal] figure mismatch  passed=(monthly={}, totalCost={}, totalMargin={}, annual={}) "
                            + "recomputed=(monthly={}, totalCost={}, totalMargin={}, annual={})",
                    monthlyMargin, totalCustomerCost, totalMarginOverTerm, effectiveAnnualizedMargin,
                    round2(recompMonthly), round2(recompCost), round2(recompMargin), round2(recompAnnual));
        }

        String slug = slug(customerName);
        if (slug.isEmpty()) {
            slug = "customer";
        }
        String fileName = "proposal-" + slug + "-" + System.currentTimeMillis() + ".docx";
        Path file = outputDir.resolve(fileName);

        long size;
        try {
            Files.createDirectories(outputDir);
            try (XWPFDocument doc = new XWPFDocument();
                 OutputStream out = Files.newOutputStream(file)) {
                writeDoc(doc, customerName, currency, monthlyConsumption, marginPercent, termMonths,
                        monthlyMargin, totalCustomerCost, totalMarginOverTerm, effectiveAnnualizedMargin,
                        valueProp);
                doc.write(out);
            }
            size = Files.size(file);
        } catch (RuntimeException | IOException e) {
            // Spring AI's MethodToolCallback converts tool exceptions to a text
            // message returned to the model, which masks the real cause. Log
            // it ourselves so it's visible.
            log.error("[tool:proposal] failed  file={} customerName={}", file, customerName, e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }

        ProposalDoc result = new ProposalDoc(file.toAbsolutePath().toString(), fileName, size);
        log.info("[tool:proposal] result  filePath={} fileName={} sizeBytes={}",
                result.filePath(), result.fileName(), result.sizeBytes());
        return result;
    }

    private static void writeDoc(XWPFDocument doc, String customerName, String currency,
                                  double monthlyConsumption, double marginPercent, int termMonths,
                                  double monthlyMargin, double totalCustomerCost,
                                  double totalMarginOverTerm, double effectiveAnnualizedMargin,
                                  String valueProp) {
        XWPFParagraph title = doc.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = title.createRun();
        titleRun.setText("Proposal for " + customerName);
        titleRun.setBold(true);
        titleRun.setFontSize(18);

        XWPFParagraph subtitle = doc.createParagraph();
        subtitle.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun subRun = subtitle.createRun();
        subRun.setText(termMonths + "-month Microsoft CSP reseller engagement");
        subRun.setItalic(true);

        XWPFParagraph blank = doc.createParagraph();
        blank.createRun().setText("");

        XWPFParagraph header = doc.createParagraph();
        XWPFRun headerRun = header.createRun();
        headerRun.setText("Financial summary");
        headerRun.setBold(true);
        headerRun.setFontSize(14);

        // createTable(1, 2) seeds the header row with two cells; subsequent
        // createRow() calls inherit that cell count. The no-arg createTable()
        // returns a 1x1 grid which would NPE on cell 1.
        XWPFTable table = doc.createTable(1, 2);
        setRow(table.getRow(0), "Metric", "Value", true);
        addRow(table, "Monthly Azure consumption", fmtMoney(monthlyConsumption, currency));
        addRow(table, "Reseller margin",           String.format(Locale.ROOT, "%.1f%%", marginPercent));
        addRow(table, "Contract term",             termMonths + " months");
        addRow(table, "Monthly margin",            fmtMoney(monthlyMargin, currency));
        addRow(table, "Total customer cost",       fmtMoney(totalCustomerCost, currency));
        addRow(table, "Total margin over term",    fmtMoney(totalMarginOverTerm, currency));
        addRow(table, "Effective annualized margin", fmtMoney(effectiveAnnualizedMargin, currency));

        if (valueProp != null && !valueProp.isBlank()) {
            XWPFParagraph valuePropHeader = doc.createParagraph();
            XWPFRun vpHeaderRun = valuePropHeader.createRun();
            vpHeaderRun.setText("Value proposition");
            vpHeaderRun.setBold(true);
            vpHeaderRun.setFontSize(14);

            XWPFParagraph valuePropPara = doc.createParagraph();
            valuePropPara.createRun().setText(valueProp);
        }

        XWPFParagraph footer = doc.createParagraph();
        XWPFRun footerRun = footer.createRun();
        footerRun.setItalic(true);
        footerRun.setFontSize(9);
        footerRun.setText("Generated " + Instant.now() + " by Solutions Copilot.");
    }

    private static void addRow(XWPFTable table, String metric, String value) {
        XWPFTableRow row = table.createRow();
        setRow(row, metric, value, false);
    }

    private static void setRow(XWPFTableRow row, String metric, String value, boolean bold) {
        row.getCell(0).removeParagraph(0);
        row.getCell(1).removeParagraph(0);
        XWPFRun mRun = row.getCell(0).addParagraph().createRun();
        mRun.setText(metric);
        mRun.setBold(bold);
        XWPFRun vRun = row.getCell(1).addParagraph().createRun();
        vRun.setText(value);
        vRun.setBold(bold);
    }

    private static String fmtMoney(double amount, String currency) {
        return String.format(Locale.ROOT, "%,.2f %s", amount, currency);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String slug(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}

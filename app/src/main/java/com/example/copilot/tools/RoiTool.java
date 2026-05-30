package com.example.copilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class RoiTool {

    private static final Logger log = LoggerFactory.getLogger(RoiTool.class);

    public record RoiEstimate(
            double monthlyMargin,
            double totalCustomerCost,
            double totalMarginOverTerm,
            double effectiveAnnualizedMargin
    ) {}

    @Tool(description = """
            Compute deterministic reseller margin / ROI / TCO figures for a
            Microsoft CSP-style reseller contract. The caller MUST invoke this
            tool for any arithmetic involving margin, ROI, TCO, monthly margin,
            total margin, customer cost, or annualized margin -- do not compute
            these yourself. Inputs are the customer's monthly Azure consumption
            (in their currency, units only -- do not pass a symbol), the reseller
            margin as a percent (e.g. 17.4 means 17.4%), and the contract term
            in whole months. Returns monthly margin, total customer cost, total
            margin over the term, and effective annualized margin (monthly * 12).
            """)
    public RoiEstimate estimateReseller(
            @ToolParam(description = "monthly Azure consumption, currency units only") double monthlyConsumption,
            @ToolParam(description = "reseller margin as a percent, e.g. 17.4")        double marginPercent,
            @ToolParam(description = "contract term in whole months, e.g. 12")         int termMonths
    ) {
        log.info("[tool:roi] called  monthlyConsumption={} marginPercent={} termMonths={}",
                monthlyConsumption, marginPercent, termMonths);

        double monthlyMargin = monthlyConsumption * marginPercent / 100.0;
        double totalCustomerCost = monthlyConsumption * termMonths;
        double totalMarginOverTerm = monthlyMargin * termMonths;
        double effectiveAnnualizedMargin = monthlyMargin * 12;

        RoiEstimate result = new RoiEstimate(
                round2(monthlyMargin),
                round2(totalCustomerCost),
                round2(totalMarginOverTerm),
                round2(effectiveAnnualizedMargin));

        log.info("[tool:roi] result  monthlyMargin={} totalCustomerCost={} totalMarginOverTerm={} effectiveAnnualizedMargin={}",
                result.monthlyMargin(), result.totalCustomerCost(),
                result.totalMarginOverTerm(), result.effectiveAnnualizedMargin());

        return result;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

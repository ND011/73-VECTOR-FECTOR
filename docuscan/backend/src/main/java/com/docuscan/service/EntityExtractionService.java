package com.docuscan.service;

import com.docuscan.model.ExtractedEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EntityExtractionService: Scans OCR text and extracts key data points.
 *
 * Uses regular expressions (regex) to find patterns like:
 * - Dates in various formats
 * - Money amounts ($, USD, EUR, INR)
 * - Invoice/bill numbers
 * - Signatories and contact names
 * - Email addresses and phone numbers
 *
 * This is faster and more reliable than ML for structured documents
 * like invoices and contracts.
 */
@Service
public class EntityExtractionService {

    // ──── DATE PATTERNS ────
    private static final Pattern[] DATE_PATTERNS = {
            // MM/DD/YYYY or DD-MM-YYYY
            Pattern.compile("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\b"),
            // YYYY-MM-DD (ISO format)
            Pattern.compile("\\b(\\d{4}[/-]\\d{1,2}[/-]\\d{1,2})\\b"),
            // January 15, 2024
            Pattern.compile("\\b((?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4})\\b", Pattern.CASE_INSENSITIVE),
            // 15 January 2024
            Pattern.compile("\\b(\\d{1,2}\\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4})\\b", Pattern.CASE_INSENSITIVE),
            // Jan 15, 2024
            Pattern.compile("\\b((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\.?\\s+\\d{1,2},?\\s+\\d{4})\\b", Pattern.CASE_INSENSITIVE)
    };

    // ──── MONEY/AMOUNT PATTERNS ────
    private static final Pattern[] AMOUNT_PATTERNS = {
            // $1,234.56
            Pattern.compile("\\$[\\d,]+\\.?\\d*"),
            // USD 1,234.56 or EUR 500
            Pattern.compile("(?:USD|EUR|GBP|INR)\\s*[\\d,]+\\.?\\d*", Pattern.CASE_INSENSITIVE),
            // Rs. 50,000 or ₹50,000
            Pattern.compile("(?:Rs\\.?|\\u20B9)\\s*[\\d,]+\\.?\\d*")
    };

    // ──── TOTAL AMOUNT (contextual) ────
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
            "(?:Total|Grand\\s+Total|Sub\\s*total|Amount\\s+Due|Balance\\s+Due|Net\\s+Amount)\\s*:?\\s*([\\$\\u20AC\\u00A3\\u20B9]?\\s*[\\d,]+\\.?\\d*)",
            Pattern.CASE_INSENSITIVE
    );

    // ──── DUE DATE (contextual) ────
    private static final Pattern DUE_DATE_PATTERN = Pattern.compile(
            "(?:Due\\s+Date|Payment\\s+Due|Due\\s+By|Deadline|Expiry\\s+Date|Expiration)\\s*:?\\s*(.+?)(?:\\n|$)",
            Pattern.CASE_INSENSITIVE
    );

    // ──── INVOICE/BILL NUMBER ────
    private static final Pattern INVOICE_PATTERN = Pattern.compile(
            "(?:Invoice|Inv|Bill|Receipt|Reference|Ref)\\s*(?:#|No\\.?|Number)?\\s*:?\\s*([A-Za-z0-9][A-Za-z0-9\\-\\/]+)",
            Pattern.CASE_INSENSITIVE
    );

    // ──── SIGNATORIES ────
    private static final Pattern[] SIGNATORY_PATTERNS = {
            Pattern.compile("(?:Signed\\s+by|Signatory|Authorized\\s+by|Approved\\s+by)\\s*:?\\s*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:Prepared\\s+by|Contact\\s+Person)\\s*:?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)", Pattern.CASE_INSENSITIVE)
    };

    // ──── EMAIL ────
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"
    );

    // ──── PHONE ────
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?\\d{1,3}[\\-.\\s]?)?(?:\\(?\\d{2,4}\\)?[\\-.\\s]?)?\\d{3,4}[\\-.\\s]?\\d{4}"
    );

    /**
     * Main method: scans the full OCR text and returns all found entities.
     */
    public List<ExtractedEntity> extractEntities(String text) {
        List<ExtractedEntity> entities = new ArrayList<>();

        // Extract dates
        for (Pattern p : DATE_PATTERNS) {
            extractMatches(p, text, "DATE", "Date", entities);
        }

        // Extract money amounts
        for (Pattern p : AMOUNT_PATTERNS) {
            extractMatches(p, text, "AMOUNT", "Amount", entities);
        }

        // Extract total amounts (contextual — near "Total:" keyword)
        Matcher totalMatcher = TOTAL_PATTERN.matcher(text);
        while (totalMatcher.find()) {
            entities.add(new ExtractedEntity("TOTAL_AMOUNT", totalMatcher.group().trim(), "Total Amount"));
        }

        // Extract due dates (contextual)
        Matcher dueDateMatcher = DUE_DATE_PATTERN.matcher(text);
        while (dueDateMatcher.find()) {
            String value = dueDateMatcher.group(1).trim();
            if (value.length() > 3 && value.length() < 50) {
                entities.add(new ExtractedEntity("DUE_DATE", value, "Due Date"));
            }
        }

        // Extract invoice numbers
        Matcher invoiceMatcher = INVOICE_PATTERN.matcher(text);
        while (invoiceMatcher.find()) {
            String value = invoiceMatcher.group(1).trim();
            if (value.length() >= 2) {
                entities.add(new ExtractedEntity("INVOICE_NUMBER", value, "Invoice/Ref Number"));
            }
        }

        // Extract signatories
        for (Pattern p : SIGNATORY_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String value = m.group(1).trim();
                if (value.length() > 2 && value.length() < 60) {
                    entities.add(new ExtractedEntity("SIGNATORY", value, "Signatory"));
                }
            }
        }

        // Extract emails
        extractMatches(EMAIL_PATTERN, text, "EMAIL", "Email", entities);

        // Extract phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(text);
        while (phoneMatcher.find()) {
            String phone = phoneMatcher.group().trim();
            if (phone.length() >= 7) {
                entities.add(new ExtractedEntity("PHONE", phone, "Phone Number"));
            }
        }

        // Remove duplicates (same type + value = duplicate)
        return removeDuplicates(entities);
    }

    private void extractMatches(Pattern pattern, String text, String type, String label,
                                 List<ExtractedEntity> entities) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isEmpty()) {
                entities.add(new ExtractedEntity(type, value, label));
            }
        }
    }

    private List<ExtractedEntity> removeDuplicates(List<ExtractedEntity> entities) {
        List<ExtractedEntity> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExtractedEntity e : entities) {
            String key = e.getType() + ":" + e.getValue();
            if (seen.add(key)) {
                unique.add(e);
            }
        }
        return unique;
    }
}

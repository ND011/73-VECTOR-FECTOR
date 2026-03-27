package com.docuscan.controller;

import com.docuscan.model.DocumentResult;
import com.docuscan.model.ExtractedEntity;
import com.docuscan.service.EntityExtractionService;
import com.docuscan.service.OcrService;
import com.docuscan.service.PdfService;
import com.docuscan.service.SummaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final OcrService ocrService;
    private final PdfService pdfService;
    private final EntityExtractionService entityExtractionService;
    private final SummaryService summaryService;

    private static final String UPLOAD_DIR = "uploads";

    public DocumentController(OcrService ocrService, PdfService pdfService,
                              EntityExtractionService entityExtractionService,
                              SummaryService summaryService) {
        this.ocrService = ocrService;
        this.pdfService = pdfService;
        this.entityExtractionService = entityExtractionService;
        this.summaryService = summaryService;
        new File(UPLOAD_DIR).mkdirs();
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        long startTime = System.currentTimeMillis();

        try {
            // ── Step 1: Validate ──
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No file provided"));
            }

            String lowerName = originalFilename.toLowerCase();
            if (!lowerName.endsWith(".pdf") && !lowerName.endsWith(".png") &&
                !lowerName.endsWith(".jpg") && !lowerName.endsWith(".jpeg")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Unsupported format. Please upload PDF, PNG, or JPG."));
            }

            System.out.println("\n══════════════════════════════════════════");
            System.out.println("📄 Processing: " + originalFilename);
            System.out.println("══════════════════════════════════════════");

            // ── Step 2: Save uploaded file ──
            String fileId = UUID.randomUUID().toString();
            String extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
            String savedFileName = fileId + extension;
            Path savedPath = Paths.get(UPLOAD_DIR, savedFileName);
            file.transferTo(savedPath.toAbsolutePath().toFile());
            System.out.println("💾 File saved: " + savedPath);

            // ── Step 3: Get images for OCR ──
            List<File> imageFiles = new ArrayList<>();
            List<String> pageImageUrls = new ArrayList<>();

            if (lowerName.endsWith(".pdf")) {
                // PDF: Convert ALL pages to images
                System.out.println("📑 Converting PDF pages to images...");
                imageFiles = pdfService.convertAllPagesToImages(
                        savedPath.toFile(), UPLOAD_DIR, fileId);

                for (File img : imageFiles) {
                    pageImageUrls.add("/uploads/" + img.getName());
                }
                System.out.println("🖼️ Converted " + imageFiles.size() + " page(s)");
            } else {
                // Single image
                imageFiles.add(savedPath.toFile());
                pageImageUrls.add("/uploads/" + savedFileName);
            }

            // ── Step 4: OCR ALL pages ──
            System.out.println("🔍 Running OCR on " + imageFiles.size() + " page(s)...");
            StringBuilder allText = new StringBuilder();

            for (int i = 0; i < imageFiles.size(); i++) {
                System.out.println("   📖 OCR page " + (i + 1) + "/" + imageFiles.size());
                String pageText = ocrService.extractText(imageFiles.get(i));

                if (imageFiles.size() > 1) {
                    allText.append("\n═══ PAGE ").append(i + 1).append(" ═══\n\n");
                }
                allText.append(pageText).append("\n");
            }

            String extractedText = allText.toString().trim();
            System.out.println("📝 Total extracted: " + extractedText.length() + " characters from "
                    + imageFiles.size() + " page(s)");

            // ── Step 5: Extract entities ──
            System.out.println("🏷️ Extracting entities...");
            List<ExtractedEntity> entities = entityExtractionService.extractEntities(extractedText);
            System.out.println("🔑 Found " + entities.size() + " entities");

            // ── Step 6: Generate summary ──
            System.out.println("�� Generating summary...");
            String summary = summaryService.generateSummary(extractedText);

            // ── Step 7: Build response ──
            long processingTime = System.currentTimeMillis() - startTime;

            DocumentResult result = new DocumentResult();
            result.setExtractedText(extractedText);
            result.setEntities(entities);
            result.setSummary(summary);
            result.setImageUrl(pageImageUrls.get(0));       // First page
            result.setPageImageUrls(pageImageUrls);          // ALL pages
            result.setPageCount(imageFiles.size());
            result.setFileName(originalFilename);
            result.setProcessingTimeMs(processingTime);

            System.out.println("══════════════════════════════════════════");
            System.out.println("✅ Done in " + processingTime + "ms");
            System.out.println("   Pages: " + imageFiles.size());
            System.out.println("   Characters: " + extractedText.length());
            System.out.println("   Entities: " + entities.size());
            System.out.println("══════════════════════════════════════════\n");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error processing document: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "running",
                "service", "DocuScan API"
        ));
    }
}

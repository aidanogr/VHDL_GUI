package vdml.io;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import vdml.ui.DiagramCanvas;

public final class DiagramPdfExporter {
    private static final float MARGIN = 24f;
    private static final int PIXELS_PER_POINT = 4;

    private DiagramPdfExporter() {
    }

    public static void export(DiagramCanvas canvas, Path destination) throws IOException {
        Dimension diagramSize = canvas.getExportSize();
        boolean landscape = diagramSize.width >= diagramSize.height;
        PDRectangle a4 = PDRectangle.A4;
        float pageWidth = landscape ? a4.getHeight() : a4.getWidth();
        float pageHeight = landscape ? a4.getWidth() : a4.getHeight();
        float imageWidth = pageWidth - 2 * MARGIN;
        float imageHeight = pageHeight - 2 * MARGIN;
        int pixelWidth = Math.round(imageWidth * PIXELS_PER_POINT);
        int pixelHeight = Math.round(imageHeight * PIXELS_PER_POINT);
        BufferedImage image = canvas.renderExportImage(pixelWidth, pixelHeight);

        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle("vdml - " + canvas.getTopEntityName());
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            document.addPage(page);
            PDImageXObject pageImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pageImage, MARGIN, MARGIN, imageWidth, imageHeight);
            }
            document.save(destination.toFile());
        }
    }
}

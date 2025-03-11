/*
 * Copyright (c) 2024 - The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMekLab.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MegaMek. If not, see <http://www.gnu.org/licenses/>.
 */

package megameklab.ui.generalUnit;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.ext.awt.RenderingHintsKeyExt;
import org.apache.batik.ext.awt.image.GraphicsUtil;
import org.apache.fop.svg.GraphicsConfiguration;

import megamek.common.Entity;
import megameklab.printing.PaperSize;
import megameklab.printing.PrintRecordSheet;
import megameklab.printing.PrintSmallUnitSheet;
import megameklab.printing.RecordSheetOptions;
import megameklab.util.UnitPrintManager;

/**
 * @author pavelbraginskiy
 *         Simply fills itself with the record sheet for the given unit.
 */
public class RecordSheetPreviewPanel extends JPanel {
    private class RightClickListener extends MouseAdapter {
        private final JPopupMenu popup = new JPopupMenu();
        {
            var copyItem = new JMenuItem("Copy to clipboard");
            copyItem.addActionListener(l -> {
                copyRecordSheetToClipboard();
            });
            popup.add(copyItem);

            var resetItem = new JMenuItem("Reset view");
            resetItem.addActionListener(l -> resetView());
            popup.add(resetItem);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            super.mouseClicked(e);
            if (e.getButton() != MouseEvent.BUTTON3) {
                return;
            }
            popup.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    // Zoom and pan state
    private final double INITIAL_ZOOM = 1.0;
    private final double MIN_ZOOM = 1.0;
    private final double MAX_ZOOM = 4.0;
    private final double ZOOM_STEP = 0.2;
    private final double CLIPBOARD_ZOOM_SCALE = 4.0;
    private double zoomFactor = INITIAL_ZOOM;

    private Point2D panOffset = new Point2D.Double(0, 0);
    private Point lastMousePoint;
    private boolean isPanning = false;
    private boolean isHighQuality = true; // Track rendering quality mode

    // High-resolution cached image for optimized rendering
    private BufferedImage cachedImage;
    private boolean needsRedraw = true;

    public RecordSheetPreviewPanel() {
        addMouseListener(new RightClickListener());
        setupMouseHandlers();
        // Enable better rendering
        setDoubleBuffered(true);
        // Request optimal GPU acceleration
        System.setProperty("sun.java2d.opengl", "true");
        System.setProperty("sun.java2d.d3d", "true");
        System.setProperty("sun.java2d.ddforcevram", "true");
        System.setProperty("sun.java2d.managedimages", "true");
    }

    /**
     * Calculate the minimum zoom level needed to fit the record sheet vertically
     * within the window.
     * 
     * @return The minimum zoom factor
     */
    private double getMinimumZoom() {
        if (cachedImage == null || getHeight() <= 0) {
            return INITIAL_ZOOM; // Default if we can't calculate
        }

        // Calculate the zoom factor needed to fit the height of the component
        double imageHeight = cachedImage.getHeight();
        double availableHeight = getHeight();

        // Calculate how much we need to scale the high-res image to fit the height
        double minZoom = availableHeight / imageHeight * MAX_ZOOM;
        return Math.max(MIN_ZOOM, minZoom);
    }

    /**
     * Creates a complete image of the record sheet and copies it to the clipboard
     */
    private void copyRecordSheetToClipboard() {
        if (entity == null) {
            return;
        }

        // Use standard paper size for the clipboard image
        RecordSheetOptions options = new RecordSheetOptions();
        PaperSize pz = options.getPaperSize();
        int imgWidth = (int) Math.ceil(pz.pxWidth * CLIPBOARD_ZOOM_SCALE);
        int imgHeight = (int) Math.ceil(pz.pxHeight * CLIPBOARD_ZOOM_SCALE);

        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = GraphicsUtil.createGraphics(img);

        // Set high quality rendering
        RenderingHints rh = new RenderingHints(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        rh.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        rh.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        rh.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        rh.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        rh.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        rh.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        rh.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        rh.put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        rh.put(org.apache.batik.ext.awt.RenderingHintsKeyExt.KEY_TRANSCODING,
                org.apache.batik.ext.awt.RenderingHintsKeyExt.VALUE_TRANSCODING_PRINTING);
        g.setRenderingHints(rh);

        // White background
        g.setBackground(Color.WHITE);
        g.clearRect(0, 0, imgWidth, imgHeight);

        // Render the record sheet directly without zoom/pan
        PrintRecordSheet sheet = UnitPrintManager.createSheets(List.of(entity), true, options)
                .stream().findFirst().orElse(null);

        if (sheet != null) {
            PageFormat pf = new PageFormat();
            if (sheet instanceof PrintSmallUnitSheet) {
                pf.setPaper(options.getPaperSize().createPaper());
                sheet.createDocument(0, pf, false);
            } else {
                pf.setPaper(options.getPaperSize().createPaper(5, 5, 5, 5));
                sheet.createDocument(0, pf, true);
            }

            GraphicsNode gn = sheet.build();

            // Scale to fit the clipboard image
            var bounds = gn.getBounds();
            var yscale = (imgHeight - 20) / bounds.getHeight();
            var xscale = (imgWidth - 20) / bounds.getWidth();
            var scale = Math.min(yscale, xscale);

            // Center the sheet in the image
            double centerX = (imgWidth - (bounds.getWidth() * scale)) / 2;
            double centerY = (imgHeight - (bounds.getHeight() * scale)) / 2;

            AffineTransform transform = new AffineTransform();
            transform.translate(centerX, centerY);
            transform.scale(scale, scale);
            gn.setTransform(transform);

            // Draw to the clipboard image
            gn.paint(g);
        }

        g.dispose();

        // Copy to clipboard
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] { DataFlavor.imageFlavor };
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                if (!isDataFlavorSupported(flavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }
                return img;
            }
        }, null);
    }

    private void setupMouseHandlers() {
        // Set up mouse wheel for zooming
        addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // Calculate zoom centered on mouse position
                Point mousePoint = e.getPoint();
                double oldZoom = zoomFactor;

                // Adjust zoom by scroll amount
                zoomFactor -= e.getPreciseWheelRotation() * ZOOM_STEP;
                double minZoom = getMinimumZoom();
                zoomFactor = Math.max(minZoom, Math.min(MAX_ZOOM, zoomFactor));

                if (oldZoom != zoomFactor) {
                    // Adjust pan to keep mouse position fixed
                    double zoomRatio = zoomFactor / oldZoom;

                    panOffset.setLocation(
                            mousePoint.getX() - (mousePoint.getX() - panOffset.getX()) * zoomRatio,
                            mousePoint.getY() - (mousePoint.getY() - panOffset.getY()) * zoomRatio);

                    repaint();
                }
            }
        });

        // Mouse listener for double-click and drag init
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    lastMousePoint = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    isPanning = true;
                    // Switch to low quality during panning for better performance
                    isHighQuality = false;
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    setCursor(Cursor.getDefaultCursor());
                    isPanning = false;
                    isHighQuality = true;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    resetView();
                }
            }
        });

        // Mouse motion listener for panning
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (isPanning && lastMousePoint != null) {
                    int dx = e.getX() - lastMousePoint.x;
                    int dy = e.getY() - lastMousePoint.y;

                    panOffset.setLocation(panOffset.getX() + dx, panOffset.getY() + dy);

                    lastMousePoint = e.getPoint();
                    repaint();
                }
            }
        });
    }

    private void resetView() {
        calculateFitToHeightZoom();
        panOffset.setLocation(0, 0);
        repaint();
    }

    private void calculateFitToHeightZoom() {
        if (cachedImage == null || getHeight() <= 0) {
            zoomFactor = INITIAL_ZOOM; // Default if we can't calculate
            return;
        }
        double minZoom = getMinimumZoom();
        zoomFactor = Math.min(MAX_ZOOM, minZoom);
    }

    private Entity entity;

    public void setEntity(Entity entity) {
        this.entity = entity;
        // Reset view and invalidate cached image when entity changes
        needsRedraw = true;
        cachedImage = null;
        if (entity != null) {
            renderHighResolutionImage();
        }
        resetView();
    }

    private void renderHighResolutionImage() {
        if (entity == null) {
            cachedImage = null;
            return;
        }

        RecordSheetOptions options = new RecordSheetOptions();
        PaperSize pz = options.getPaperSize();
        int fullWidth = (int) Math.ceil(pz.pxWidth * MAX_ZOOM);
        int fullHeight = (int) Math.ceil(pz.pxHeight * MAX_ZOOM);

        // Try to use hardware-accelerated image if possible
        try {
            // Get hardware acceleration configuration
            java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
            java.awt.GraphicsDevice gs = ge.getDefaultScreenDevice();
            java.awt.GraphicsConfiguration gc = gs.getDefaultConfiguration();

            // Create a compatible image (should work better with hardware)
            cachedImage = gc.createCompatibleImage(fullWidth, fullHeight, java.awt.Transparency.OPAQUE);
        } catch (Exception e) {
            // Fallback to standard image if hardware acceleration fails
            cachedImage = new BufferedImage(fullWidth, fullHeight, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = GraphicsUtil.createGraphics(cachedImage);
        g.setComposite(java.awt.AlphaComposite.SrcOver);

        // Set render quality for the high-res image
        RenderingHints rh = new RenderingHints(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        rh.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        rh.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        rh.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        rh.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        rh.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        rh.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        rh.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        rh.put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        rh.put(org.apache.batik.ext.awt.RenderingHintsKeyExt.KEY_TRANSCODING,
                org.apache.batik.ext.awt.RenderingHintsKeyExt.VALUE_TRANSCODING_VECTOR);
        rh.put(RenderingHintsKeyExt.KEY_BUFFERED_IMAGE, new WeakReference<BufferedImage>(cachedImage));
        g.setRenderingHints(rh);

        // White background
        g.setBackground(Color.WHITE);
        g.clearRect(0, 0, fullWidth, fullHeight);

        // Render the record sheet to the high-res image
        PrintRecordSheet sheet = UnitPrintManager.createSheets(List.of(entity), true, options)
                .stream().findFirst().orElse(null);

        if (sheet != null) {
            PageFormat pf = new PageFormat();
            if (sheet instanceof PrintSmallUnitSheet) {
                pf.setPaper(options.getPaperSize().createPaper());
                sheet.createDocument(0, pf, false);
            } else {
                pf.setPaper(options.getPaperSize().createPaper(5, 5, 5, 5));
                sheet.createDocument(0, pf, true);
            }

            GraphicsNode gn = sheet.build();

            // Scale to fit the high-resolution image
            var bounds = gn.getBounds();
            var yscale = (fullHeight - 20) / bounds.getHeight();
            var xscale = (fullWidth - 20) / bounds.getWidth();
            var scale = Math.min(yscale, xscale);

            // Center the sheet in the image
            double centerX = (fullWidth - (bounds.getWidth() * scale)) / 2;
            double centerY = (fullHeight - (bounds.getHeight() * scale)) / 2;

            AffineTransform transform = new AffineTransform();
            transform.translate(centerX, centerY);
            transform.scale(scale, scale);
            gn.setTransform(transform);

            // Draw to the high-res image
            gn.paint(g);
        }

        g.dispose();
        needsRedraw = false;
    }

    private void paintComponent(Graphics2D g, int width, int height) {
        // Set render hints based on quality mode
        RenderingHints rh = new RenderingHints(
                RenderingHints.KEY_RENDERING,
                isHighQuality ? RenderingHints.VALUE_RENDER_QUALITY : RenderingHints.VALUE_RENDER_SPEED);
        rh.put(RenderingHints.KEY_INTERPOLATION,
                isHighQuality ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        rh.put(RenderingHints.KEY_ANTIALIASING,
                isHighQuality ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        rh.put(RenderingHints.KEY_TEXT_ANTIALIASING,
                isHighQuality ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        rh.put(RenderingHints.KEY_FRACTIONALMETRICS,
                isHighQuality ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        rh.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setRenderingHints(rh);

        // White background
        g.setBackground(Color.WHITE);
        g.clearRect(0, 0, width, height);

        if (entity != null && cachedImage != null) {
            // Generate high-resolution image if needed
            if (needsRedraw) {
                renderHighResolutionImage();
            }

            if (cachedImage != null) {
                // Store original transform for restoration later
                AffineTransform originalTransform = g.getTransform();

                double srcX = Math.max(0, -panOffset.getX() * (MAX_ZOOM / zoomFactor));
                double srcY = Math.max(0, -panOffset.getY() * (MAX_ZOOM / zoomFactor));
                double srcWidth = cachedImage.getWidth() - srcX;
                double srcHeight = cachedImage.getHeight() - srcY;

                // Destination region (where to draw in the panel)
                double destX = Math.max(0, panOffset.getX());
                double destY = Math.max(0, panOffset.getY());
                double destWidth = srcWidth * (zoomFactor / MAX_ZOOM);
                double destHeight = srcHeight * (zoomFactor / MAX_ZOOM);

                // Draw the image
                g.drawImage(
                        cachedImage,
                        (int) destX, (int) destY,
                        (int) (destX + destWidth), (int) (destY + destHeight),
                        (int) srcX, (int) srcY,
                        (int) (srcX + srcWidth), (int) (srcY + srcHeight),
                        null);

                // Restore original transform
                g.setTransform(originalTransform);
            }
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        paintComponent((Graphics2D) g, getWidth(), getHeight());
    }

    // Add a component resize listener to adjust the view on resize
    @Override
    public void addNotify() {
        super.addNotify();
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // Only auto-fit if we haven't manually zoomed or panned yet
                // (This prevents resetting user's view when window is resized)
                if (cachedImage != null && panOffset.getX() == 0.0 && panOffset.getY() == 0.0
                        && zoomFactor == INITIAL_ZOOM) {
                    resetView();
                }
            }
        });
    }
}

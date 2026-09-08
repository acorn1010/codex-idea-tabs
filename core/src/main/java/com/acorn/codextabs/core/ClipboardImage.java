package com.acorn.codextabs.core;

import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Supplies image pixels to the IDE clipboard instead of copying an attachment path as text. */
public final class ClipboardImage implements Transferable {
    private final BufferedImage image;
    private ClipboardImage(BufferedImage image) { this.image = image; }

    public static ClipboardImage fromPng(String data) throws IOException {
        if (data.length() > 70_000_000) { throw new IllegalArgumentException("Image exceeds the clipboard size limit."); }
        byte[] bytes = Base64.getDecoder().decode(data);
        if (bytes.length > 50 * 1024 * 1024) { throw new IllegalArgumentException("Image exceeds 50 MB."); }
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) { throw new IllegalArgumentException("The copied image could not be decoded."); }
        return new ClipboardImage(image);
    }
    @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
    @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
    @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavor)) { throw new UnsupportedFlavorException(flavor); }
        return image;
    }
}

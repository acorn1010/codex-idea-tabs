package com.acorn.codextabs.core;

import org.junit.jupiter.api.Test;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardImageTest {
    @Test void copiesFullImagePixelsAndAlphaWithoutOfferingAPath() throws Exception {
        var source = new BufferedImage(640, 280, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(400, 200, 0x7f123456);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(source, "png", bytes);
        var clipboard = ClipboardImage.fromPng(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        var image = (BufferedImage) clipboard.getTransferData(DataFlavor.imageFlavor);
        assertEquals(640, image.getWidth()); assertEquals(280, image.getHeight());
        assertEquals(0x7f123456, image.getRGB(400, 200));
        assertFalse(clipboard.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertThrows(UnsupportedFlavorException.class, () -> clipboard.getTransferData(DataFlavor.stringFlavor));
    }
    @Test void rejectsInvalidImageBytes() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardImage.fromPng("Not base64!"));
        assertThrows(IllegalArgumentException.class, () -> ClipboardImage.fromPng(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
    }
}

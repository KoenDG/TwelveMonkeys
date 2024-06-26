/*
 * Copyright (c) 2008, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.twelvemonkeys.image;

import com.twelvemonkeys.lang.Validate;

import java.awt.*;
import java.awt.image.*;
import java.lang.reflect.Array;
import java.util.EventListener;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A faster, lighter and easier way to convert an {@code Image} to a
 * {@code BufferedImage} than using a {@code PixelGrabber}.
 * Clients may provide progress listeners to monitor conversion progress.
 * <p>
 * Supports source image subsampling and source region extraction.
 * Supports source images with 16 bit {@link ColorModel} and
 * {@link DataBuffer#TYPE_USHORT} transfer type, without converting to
 * 32 bit/TYPE_INT.
 * </p>
 * <p>
 * NOTE: Does not support images with more than one {@code ColorModel} or
 * different types of pixel data. This is not very common.
 * </p>
 *
 * @author <a href="mailto:harald.kuhr@gmail.com">Harald Kuhr</a>
 * @version $Id: //depot/branches/personal/haraldk/twelvemonkeys/release-2/twelvemonkeys-core/src/main/java/com/twelvemonkeys/image/BufferedImageFactory.java#1 $
 */
public final class BufferedImageFactory {
    private List<ProgressListener> listeners;
    private int percentageDone;

    private ImageProducer producer;
    private ImageConversionException consumerException;
    private volatile boolean fetching;
    private boolean readColorModelOnly;

    private int x = 0;
    private int y = 0;
    private int width = -1;
    private int height = -1;

    private int xSub = 1;
    private int ySub = 1;

    private int offset;
    private int scanSize;

    private ColorModel sourceColorModel;
    private Hashtable<?, ?> sourceProperties; // ImageConsumer API dictates Hashtable

    private Object sourcePixels;

    private BufferedImage buffered;
    private ColorModel colorModel;

    // NOTE: Just to not expose the inheritance
    private final Consumer consumer = new Consumer();

    /**
     * Creates a {@code BufferedImageFactory}.
     * @param source the source image
     * @throws IllegalArgumentException if {@code source == null}
     */
    public BufferedImageFactory(final Image source) {
        this(source != null ? source.getSource() : null);
    }

    /**
     * Creates a {@code BufferedImageFactory}.
     * @param source the source image producer
     * @throws IllegalArgumentException if {@code source == null}
     */
    public BufferedImageFactory(final ImageProducer source) {
        Validate.notNull(source, "source");
        producer = source;
    }

    /**
     * Returns the {@code BufferedImage} extracted from the given
     * {@code ImageSource}. Multiple requests will return the same image.
     *
     * @return the {@code BufferedImage}
     *
     * @throws ImageConversionException if the given {@code ImageSource} cannot
     * be converted for some reason.
     */
    public BufferedImage getBufferedImage() throws ImageConversionException {
        doFetch(false);
        return buffered;
    }

    /**
     * Returns the {@code ColorModel} extracted from the
     * given {@code ImageSource}. Multiple requests will return the same model.
     *
     * @return the {@code ColorModel}
     *
     * @throws ImageConversionException if the given {@code ImageSource} cannot
     * be converted for some reason.
     */
    public ColorModel getColorModel() throws ImageConversionException {
        doFetch(true);
        return buffered != null ? buffered.getColorModel() : colorModel;
    }

    /**
     * Frees resources used by this {@code BufferedImageFactory}.
     */
    public void dispose() {
        freeResources();
        buffered = null;
        colorModel = null;
    }

    /**
     * Aborts the image production.
     */
    public void abort() {
        consumer.imageComplete(ImageConsumer.IMAGEABORTED);
    }

    /**
     * Sets the source region (AOI) for the new image.
     *
     * @param region the source region
     */
    public void setSourceRegion(final Rectangle region) {
        // Re-fetch everything, if region changed
        if (x != region.x || y != region.y || width != region.width || height != region.height) {
            dispose();
        }

        x = region.x;
        y = region.y;
        width = region.width;
        height = region.height;
    }

    /**
     * Sets the source subsampling for the new image.
     *
     * @param xSubsampling horizontal subsampling factor
     * @param ySubsampling vertical subsampling factor
     */
    public void setSourceSubsampling(int xSubsampling, int ySubsampling) {
        // Re-fetch everything, if subsampling changed
        if (xSub != xSubsampling || ySub != ySubsampling) {
            dispose();
        }

        if (xSubsampling > 1) {
            xSub = xSubsampling;
        }
        if (ySubsampling > 1) {
            ySub = ySubsampling;
        }
    }

    private synchronized void doFetch(final boolean colorModelOnly) throws ImageConversionException {
        if (!fetching && (!colorModelOnly && buffered == null || buffered == null && sourceColorModel == null)) {
            // NOTE: Subsampling is only applied if extracting full image
            if (!colorModelOnly && (xSub > 1 || ySub > 1)) {
                // If only sampling a region, the region must be scaled too
                if (width > 0 && height > 0) {
                    width = (width + xSub - 1) / xSub;
                    height = (height + ySub - 1) / ySub;

                    x = (x + xSub - 1) / xSub;
                    y = (y + ySub - 1) / ySub;
                }

                producer = new FilteredImageSource(producer, new SubsamplingFilter(xSub, ySub));
            }

            // Start fetching
            fetching = true;
            readColorModelOnly = colorModelOnly;

            producer.startProduction(consumer); // Note: If single-thread (synchronous), this call will block

            // Wait until the producer wakes us up, by calling imageComplete
            while (fetching) {
                try {
                    wait(200L);
                }
                catch (InterruptedException e) {
                    throw new ImageConversionException("Image conversion aborted: " + e.getMessage(), e);
                }
            }

            try {
                if (consumerException != null) {
                    throw new ImageConversionException("Image conversion failed: " + consumerException.getMessage(), consumerException);
                }

                if (colorModelOnly) {
                    createColorModel();
                }
                else {
                    createBuffered();
                }
            }
            finally {
                // Clean up, in case any objects are copied/cloned, so we can free resources
                freeResources();
            }
        }
    }

    private void createColorModel() {
        colorModel = sourceColorModel;
    }

    private void createBuffered() {
        if (width > 0 && height > 0) {
            if (sourceColorModel != null && sourcePixels != null) {
                // TODO: Fix pixel size / color model problem
                WritableRaster raster = ImageUtil.createRaster(width, height, sourcePixels, sourceColorModel);
                buffered = new BufferedImage(sourceColorModel, raster, sourceColorModel.isAlphaPremultiplied(), sourceProperties);
            }
            else {
                buffered = ImageUtil.createClear(width, height, null);
            }
        }

        if (buffered == null) {
            throw new ImageConversionException("Could not create BufferedImage");
        }
    }

    private void freeResources() {
        sourceColorModel = null;
        sourcePixels = null;
        sourceProperties = null;
    }

    private void processProgress(int scanline) {
        if (listeners != null) {
            int percent = 100 * scanline / height;

            if (percent > percentageDone) {
                percentageDone = percent;

                for (ProgressListener listener : listeners) {
                    listener.progress(this, percent);
                }
            }
        }
    }

    /**
     * Adds a progress listener to this factory.
     *
     * @param listener the progress listener
     */
    public void addProgressListener(ProgressListener listener) {
        if (listener == null) {
            return;
        }

        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<>();
        }

        listeners.add(listener);
    }

    /**
     * Removes a progress listener from this factory.
     *
     * @param listener the progress listener
     */
    public void removeProgressListener(ProgressListener listener) {
        if (listener == null) {
            return;
        }

        if (listeners == null) {
            return;
        }

        listeners.remove(listener);
    }

    /**
     * Removes all progress listeners from this factory.
     */
    public void removeAllProgressListeners() {
        if (listeners != null) {
            listeners.clear();
        }
    }

    /**
     * Converts an array of {@code int} pixels to an array of {@code short}
     * pixels. The conversion is done, by masking out the
     * <em>higher 16 bits</em> of the {@code int}.
     * <p>
     * For any given {@code int}, the {@code short} value is computed as
     * follows:
     * <blockquote>{@code
     * short value = (short) (intValue & 0x0000ffff);
     * }</blockquote>
     * </p>
     *
     * @param inputPixels the pixel data to convert
     * @return an array of {@code short}s, same length as {@code inputPixels}
     */
    private static short[] toShortPixels(int[] inputPixels) {
        short[] pixels = new short[inputPixels.length];

        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (short) (inputPixels[i] & 0xffff);
        }

        return pixels;
    }

    /**
     * This interface allows clients of a {@code BufferedImageFactory} to
     * receive notifications of decoding progress.
     *
     * @see BufferedImageFactory#addProgressListener
     * @see BufferedImageFactory#removeProgressListener
     */
    public interface ProgressListener extends EventListener {

        /**
         * Reports progress to this listener.
         * Invoked by the {@code BufferedImageFactory} to report progress in
         * the image decoding.
         *
         * @param factory the factory reporting the progress
         * @param percentage the percentage of progress
         */
        void progress(BufferedImageFactory factory, float percentage);
    }

    private class Consumer implements ImageConsumer {
        /**
         * Implementation of all setPixels methods.
         * Note that this implementation assumes that all invocations for one
         * image uses the same color model, and that the pixel data has the
         * same type.
         *
         * @param pX x coordinate of pixel data region
         * @param pY y coordinate of pixel data region
         * @param pWidth width of pixel data region
         * @param pHeight height of pixel data region
         * @param pModel the color model of the pixel data
         * @param pPixels the pixel data array
         * @param pOffset the offset into the pixel data array
         * @param pScanSize the scan size of the pixel data array
         */
        @SuppressWarnings({"SuspiciousSystemArraycopy"})
        private void setPixelsImpl(int pX, int pY, int pWidth, int pHeight, ColorModel pModel, Object pPixels, int pOffset, int pScanSize) {
            setColorModelOnce(pModel);

            if (pPixels == null) {
                return;
            }

            // Allocate array if necessary
            if (sourcePixels == null) {
                // Allocate a suitable source pixel array
                // TODO: Should take pixel "width" into consideration, for byte packed rasters?!
                // OR... Is anything but single-pixel models really supported by the API?
                sourcePixels = Array.newInstance(pPixels.getClass().getComponentType(), width * height);
                scanSize = width;
                offset = 0;
            }
            else if (sourcePixels.getClass() != pPixels.getClass()) {
                throw new IllegalStateException("Only one pixel type allowed");
            }

            // AOI stuff
            if (pY < y) {
                int diff = y - pY;
                if (diff >= pHeight) {
                    return;
                }
                pOffset += pScanSize * diff;
                pY += diff;
                pHeight -= diff;
            }
            if (pY + pHeight > y + height) {
                pHeight = (y + height) - pY;
                if (pHeight <= 0) {
                    return;
                }
            }

            if (pX < x) {
                int diff = x - pX;
                if (diff >= pWidth) {
                    return;
                }
                pOffset += diff;
                pX += diff;
                pWidth -= diff;
            }
            if (pX + pWidth > x + width) {
                pWidth = (x + width) - pX;
                if (pWidth <= 0) {
                    return;
                }
            }

            int dstOffset = offset + (pY - y) * scanSize + (pX - x);

            // Do the pixel copying
            for (int i = pHeight; i > 0; i--) {
                System.arraycopy(pPixels, pOffset, sourcePixels, dstOffset, pWidth);
                pOffset += pScanSize;
                dstOffset += scanSize;
            }

            processProgress(pY + pHeight);
        }

        public void setPixels(int x, int y, int width, int height, ColorModel colorModel, short[] pixels, int offset, int scanSize) {
            setPixelsImpl(x, y, width, height, colorModel, pixels, offset, scanSize);
        }

        private void setColorModelOnce(final ColorModel colorModel) {
            // NOTE: There seems to be a "bug" in AreaAveragingScaleFilter, as it
            // first passes the original color model through in setColorModel, then
            // later replaces it with the default RGB in the first setPixels call
            // (this is probably allowed according to the spec, but it's a waste of time and space).
            if (sourceColorModel != colorModel) {
                if (sourcePixels == null) {
                    sourceColorModel = colorModel;
                }
                else {
                    throw new IllegalStateException("Change of ColorModel after pixel delivery not supported");
                }
            }

            // If color model is all we ask for, stop now
            if (readColorModelOnly) {
                consumer.imageComplete(ImageConsumer.IMAGEABORTED);
            }
        }

        @Override
        public void imageComplete(int status) {
            fetching = false;

            if (producer != null) {
                producer.removeConsumer(this);
            }

            if (status == ImageConsumer.IMAGEERROR) {
                consumerException = new ImageConversionException("ImageConsumer.IMAGEERROR");
            }

            synchronized (BufferedImageFactory.this) {
                BufferedImageFactory.this.notifyAll();
            }
        }

        @Override
        public void setColorModel(ColorModel colorModel) {
            setColorModelOnce(colorModel);
        }

        @Override
        public void setDimensions(int w, int h) {
            if (width < 0) {
                width = w - x;
            }
            if (height < 0) {
                height = h - y;
            }

            // Hmm.. Special case, but is it a good idea?
            if (width <= 0 || height <= 0) {
                imageComplete(ImageConsumer.STATICIMAGEDONE);
            }
        }

        @Override
        public void setHints(int hintFlags) {
           // ignore
        }

        @Override
        public void setPixels(int x, int y, int width, int height, ColorModel colorModel, byte[] pixels, int offset, int scanSize) {
            setPixelsImpl(x, y, width, height, colorModel, pixels, offset, scanSize);
        }

        @Override
        public void setPixels(int x, int y, int width, int height, ColorModel colorModel, int[] pixels, int offset, int scanSize) {
            if (colorModel.getTransferType() == DataBuffer.TYPE_USHORT) {
                // NOTE: Workaround for limitation in ImageConsumer API
                // Convert int[] to short[], to be compatible with the ColorModel
                setPixelsImpl(x, y, width, height, colorModel, toShortPixels(pixels), offset, scanSize);
            }
            else {
                setPixelsImpl(x, y, width, height, colorModel, pixels, offset, scanSize);
            }
        }

        @Override
        public void setProperties(Hashtable properties) {
            sourceProperties = properties;
        }
    }

    /*
    public static void main(String[] args) throws InterruptedException {
        Image image = Toolkit.getDefaultToolkit().createImage(args[0]);
        System.err.printf("image: %s (which is %sa buffered image)\n", image, image instanceof BufferedImage ? "" : "not ");

        int warmUpLoops = 500;
        int testLoops = 100;

        for (int i = 0; i < warmUpLoops; i++) {
            // Warm up...
            convertUsingFactory(image);
            convertUsingPixelGrabber(image);
            convertUsingPixelGrabberNaive(image);
        }

        BufferedImage bufferedImage = null;
        long start = System.currentTimeMillis();
        for (int i = 0; i < testLoops; i++) {
            bufferedImage = convertUsingFactory(image);
        }
        System.err.printf("Conversion time (factory): %f ms (image: %s)\n", (System.currentTimeMillis() - start) / (double) testLoops, bufferedImage);

        start = System.currentTimeMillis();
        for (int i = 0; i < testLoops; i++) {
            bufferedImage = convertUsingPixelGrabber(image);
        }
        System.err.printf("Conversion time (grabber): %f ms (image: %s)\n", (System.currentTimeMillis() - start) / (double) testLoops, bufferedImage);

        start = System.currentTimeMillis();
        for (int i = 0; i < testLoops; i++) {
            bufferedImage = convertUsingPixelGrabberNaive(image);
        }
        System.err.printf("Conversion time (naive g): %f ms (image: %s)\n", (System.currentTimeMillis() - start) / (double) testLoops, bufferedImage);
    }

    private static BufferedImage convertUsingPixelGrabberNaive(Image image) throws InterruptedException {
        // NOTE: It does not matter if we wait for the image or not, the time is about the same as it will only happen once
        if ((image.getWidth(null) < 0 || image.getHeight(null) < 0) && !ImageUtil.waitForImage(image)) {
            System.err.printf("Could not get image dimensions for image %s\n", image.getSource());
        }

        int w = image.getWidth(null);
        int h = image.getHeight(null);
        PixelGrabber grabber = new PixelGrabber(image, 0, 0, w, h, true); // force RGB
        grabber.grabPixels();

        // Following casts are safe, as we force RGB in the pixel grabber
        int[] pixels = (int[]) grabber.getPixels();

        BufferedImage bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
//        bufferedImage.setRGB(0, 0, w, h, pixels, 0, w);
        bufferedImage.getRaster().setDataElements(0, 0, w, h, pixels);

        return bufferedImage;
    }

    private static BufferedImage convertUsingPixelGrabber(Image image) throws InterruptedException {
        // NOTE: It does not matter if we wait for the image or not, the time is about the same as it will only happen once
        if ((image.getWidth(null) < 0 || image.getHeight(null) < 0) && !ImageUtil.waitForImage(image)) {
            System.err.printf("Could not get image dimensions for image %s\n", image.getSource());
        }

        int w = image.getWidth(null);
        int h = image.getHeight(null);
        PixelGrabber grabber = new PixelGrabber(image, 0, 0, w, h, true); // force RGB
        grabber.grabPixels();

        // Following casts are safe, as we force RGB in the pixel grabber
//        DirectColorModel cm = (DirectColorModel) grabber.getColorModel();
        DirectColorModel cm = (DirectColorModel) ColorModel.getRGBdefault();
        int[] pixels = (int[]) grabber.getPixels();

        WritableRaster raster = Raster.createPackedRaster(new DataBufferInt(pixels, pixels.length), w, h, w, cm.getMasks(), null);

        return new BufferedImage(cm, raster, cm.isAlphaPremultiplied(), null);
    }

    private static BufferedImage convertUsingFactory(Image image) {
        return new BufferedImageFactory(image).getBufferedImage();
    }
    */
}
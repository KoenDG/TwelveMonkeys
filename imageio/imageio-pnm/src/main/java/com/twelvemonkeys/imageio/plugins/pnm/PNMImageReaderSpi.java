/*
 * Copyright (c) 2014, Harald Kuhr
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

package com.twelvemonkeys.imageio.plugins.pnm;

import com.twelvemonkeys.imageio.spi.ImageReaderSpiBase;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

public final class PNMImageReaderSpi extends ImageReaderSpiBase {

    /**
     * Creates a {@code PNMImageReaderSpi}.
     */
    public PNMImageReaderSpi() {
        super(new PNMProviderInfo());
    }

    @Override
    public boolean canDecodeInput(final Object source) throws IOException {
        if (!(source instanceof ImageInputStream)) {
            return false;
        }

        ImageInputStream stream = (ImageInputStream) source;

        stream.mark();

        try {
            short magic = (short) ((stream.readByte() & 0xFF) << 8 | (stream.readByte() & 0xFF));

            switch (magic) {
                case PNM.PBM_PLAIN:
                case PNM.PBM:
                case PNM.PGM_PLAIN:
                case PNM.PGM:
                case PNM.PPM_PLAIN:
                case PNM.PPM:
                case PNM.PFM_GRAY:
                case PNM.PFM_RGB:
                    return true;
                default:
                    return false;
            }
        }
        finally {
            stream.reset();
        }
    }

    @Override
    public ImageReader createReaderInstance(final Object extension) {
        return new PNMImageReader(this);
    }

    @Override
    public String getDescription(final Locale locale) {
        return "NetPBM Portable Any Map (PNM) image reader";
    }
}

/*
 * Jermit
 *
 * The MIT License (MIT)
 *
 * Copyright (C) 2018 Kevin Lamonte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * @author Kevin Lamonte [kevin.lamonte@gmail.com]
 * @version 1
 */
package jermit.protocol.zmodem;

/**
 * Zdata contains a file position and some file data.
 */
class ZData extends Header {

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * File data bytes.
     */
    private byte [] fileData;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Public constructor.
     */
    public ZData() {
        this(0);
    }

    /**
     * Public constructor.
     *
     * @param data the data field for this header
     */
    public ZData(final int data) {
        super(Type.ZDATA, (byte) 0x0A, "ZDATA", data);

        fileData = new byte[0];
    }

    // ------------------------------------------------------------------------
    // Header -----------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Parse whatever came in a data subpacket.  Used by subclasses to put
     * data into fields.
     *
     * @param subpacket the bytes of the subpacket
     */
    @Override
    protected void parseDataSubpacket(final byte [] subpacket) {
        fileData = subpacket;
    }

    // ------------------------------------------------------------------------
    // ZData ------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get the file data.
     *
     * @return the data
     */
    public byte [] getFileData() {
        return fileData;
    }

}

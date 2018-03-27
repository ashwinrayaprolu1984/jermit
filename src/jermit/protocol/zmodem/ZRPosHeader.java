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
 * ZRPosHeader contains a file position.
 */
class ZRPosHeader extends Header {

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Public constructor.
     */
    public ZRPosHeader() {
        this(0);
    }

    /**
     * Public constructor.
     *
     * @param data the data field for this header
     */
    public ZRPosHeader(final int data) {
        super(Type.ZRPOS, (byte) 0x09, "ZRPOS", data);
    }

    // ------------------------------------------------------------------------
    // Header -----------------------------------------------------------------
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // ZRPosHeader ------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get the file position.
     *
     * @return the value
     */
    public long getPosition() {
        if (data < 0) {
            return ((long) data) + 4294967296L;
        }
        return ((long) data);
    }

}

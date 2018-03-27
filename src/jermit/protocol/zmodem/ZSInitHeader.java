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
 * ZSInitHeader is sent by the sender with specified expectations.
 */
class ZSInitHeader extends Header {

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Public constructor.
     *
     * @param session the ZmodemSession
     */
    public ZSInitHeader(final ZmodemSession session) {
        super(Type.ZSINIT, (byte) 0x02, "ZSINIT", 0);

        if (session.escapeControlChars) {
            data |= ZRInitHeader.TX_ESCAPE_CTRL;
        }
        if (session.escape8BitChars) {
            data |= ZRInitHeader.TX_ESCAPE_8BIT;
        }
    }

    /**
     * Public constructor.
     *
     * @param data the data field for this header
     */
    public ZSInitHeader(final int data) {
        super(Type.ZSINIT, (byte) 0x02, "ZSINIT", data);
    }

    // ------------------------------------------------------------------------
    // Header -----------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get the data subpacket raw bytes.  Used by subclasses to serialize
     * fields into data.
     *
     * @return the bytes of the subpacket
     */
    @Override
    protected byte [] createDataSubpacket() {
        // ZSInit could use this for an "attention string".  For now, we will
        // not support the attention string.
        return new byte[0];
    }

    // ------------------------------------------------------------------------
    // ZSInitHeader -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get the flags from the remote side.
     *
     * @return the flags
     */
    public int getFlags() {
        return data;
    }

}

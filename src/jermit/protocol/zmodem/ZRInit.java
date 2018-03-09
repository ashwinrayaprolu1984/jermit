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
 * ZRInit is sent by the receiver with specified capabilities.
 */
class ZRInit extends Header {

    // ------------------------------------------------------------------------
    // Constants --------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Receiver can send and receive true full duplex.
     */
    public static final int TX_CAN_FULL_DUPLEX  = 0x00000001;

    /**
     * Receiver can receive data during disk I/O.
     */
    public static final int TX_CAN_OVERLAP_IO   = 0x00000002;

    /**
     * Receiver can send a break signal.
     */
    public static final int TX_CAN_BREAK        = 0x00000004;

    /**
     * Receiver can decrypt.
     */
    public static final int TX_CAN_DECRYPT      = 0x00000008;

    /**
     * Receiver can uncompress.
     */
    public static final int TX_CAN_LZW          = 0x00000010;

    /**
     * Receiver can use 32 bit CRC.
     */
    public static final int TX_CAN_CRC32        = 0x00000020;

    /**
     * Receiver expects control chararacters to be escaped.
     */
    public static final int TX_ESCAPE_CTRL      = 0x00000040;

    /**
     * Receiver expects 8th bit to be escaped.
     */
    public static final int TX_ESCAPE_8BIT      = 0x00000080;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Public constructor.
     *
     * @param session the ZmodemSession
     */
    public ZRInit(final ZmodemSession session) {
        super(Type.ZRINIT, (byte) 0x01, "ZRINIT", 0);

        data = TX_CAN_FULL_DUPLEX | TX_CAN_OVERLAP_IO;
        if (session.useCrc32) {
            data |= TX_CAN_CRC32;
        }
        if (session.escapeControlChars) {
            data |= TX_ESCAPE_CTRL;
        }
    }

    /**
     * Public constructor.
     *
     * @param data the data field for this header
     */
    public ZRInit(final int data) {
        super(Type.ZRINIT, (byte) 0x01, "ZRINIT", data);
    }

    /**
     * Public constructor.
     */
    public ZRInit() {
        this(0);
    }

    // ------------------------------------------------------------------------
    // Header -----------------------------------------------------------------
    // ------------------------------------------------------------------------

}

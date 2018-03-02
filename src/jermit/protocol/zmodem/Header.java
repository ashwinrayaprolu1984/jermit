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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import jermit.io.EOFInputStream;
import jermit.io.ReadTimeoutException;

/**
 * A Header represents a fixed-size message between two Zmodem endpoints.
 */
class Header {

    // ------------------------------------------------------------------------
    // Constants --------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Carriage return constant.
     */
    private static final byte C_CR = 0x0d;

    /**
     * Line feed constant.
     */
    private static final byte C_LF = 0x0a;

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    // If true, enable some debugging output.
    protected static final boolean DEBUG = ZmodemSession.DEBUG;

    /**
     * Header type flag.  These are used by the higher-level state machine,
     * so there are some types not defined by the Zmodem protocol standard.
     */
    public enum Type {

        /**
         * ZRQINIT, used by the sender to request the receiver begin a
         * transfer session.
         */
        ZRQINIT,

        /**
         * ZRINIT, containing receiver capabilities.
         */
        ZRINIT,

        /**
         * ZSINIT, containing expectations from the sender.
         */
        ZSINIT,

        /**
         * ZACK, acknowledgment to a ZSINIT, ZCHALLENGE, ZCRCQ, or ZCRCW.
         */
        ZACK,

        /**
         * ZFILE, followed by a filename and metadata.
         */
        ZFILE,

        /**
         * ZSKIP, sent by the receiver in response to ZFILE, makes the sender
         * skip to the next file.
         */
        ZSKIP,

        /**
         * ZNAK, indicates last header was garbled.
         */
        ZNAK,

        /**
         * ZABORT, sent by receiver to terminate batch file transfers when
         * requested by the user.
         */
        ZABORT,

        /**
         * ZFIN, sent by sending program to terminate a ZMODEM session.
         */
        ZFIN,

        /**
         * ZRPOS, Sent by receiver to force file transfer to resume at file
         * offset.
         */
        ZRPOS,

        /**
         * ZDATA, file data at a particular offset.
         */
        ZDATA,

        /**
         * ZEOF, sent by sender to indicate end of file.
         */
        ZEOF,

        /**
         * ZFERR, error in reading or writing file, protocol equivalent to
         * ZABORT.
         */
        ZFERR,

        /**
         * ZCRC, containing a file full or partial CRC.
         */
        ZCRC,

        /**
         * ZCHALLENGE, sent by the receiving program to the sending program
         * to verify that it is connected to an operating program, and was
         * not activated by spurious data or a Trojan Horse message.
         */
        ZCHALLENGE,

        /**
         * ZCOMPL: "Request now completed."  Part of ZCOMMAND sequence.
         *
         * Jermit will NEVER support this option.
         */
        ZCOMPL,

        /**
         * ZCAN: "This is a pseudo frame type returned by gethdr() in
         * response to a Session Abort sequence."
         *
         * The "Session Abort sequence" is 8 Ctrl-X's following by 8
         * backspaces.
         */
        ZCAN,

        /**
         * ZFREECNT: "Sending program requests a ZACK frame with ZP0...ZP3
         * containing the number of free bytes on the current file system.  A
         * value of 0 represents an indefinite amount of free space."
         *
         * Jermit will NEVER support this option.
         */
        ZFREECNT,

        /**
         * ZCOMMAND, used by a sender to execute commands on a receiver.
         *
         * Jermit will NEVER support this option.
         */
        ZCOMMAND,

    }

    /**
     * The header type.
     */
    private Type type;

    /**
     * The byte used on the wire to denote this header type.
     */
    private byte wireByte;

    /**
     * A human-readable description of this header type.
     */
    private String description;

    /**
     * decode() will set the parseState to one of these values.
     */
    public enum ParseState {

        /**
         * Header decoded OK.
         */
        OK,

        /**
         * header had a CRC error.
         */
        TRANSMIT_CRC,

        /**
         * Protocol error: TYPE field is wrong.
         */
        PROTO_TYPE,

    }

    /**
     * State after parsing data received from the wire.
     */
    protected ParseState parseState = ParseState.OK;

    /**
     * The 32-bit data field.
     */
    protected int data = 0;

    /**
     * Map of counts of CAN bytes (0x18, Ctrl-X) to InputStream.  Key is
     * System.identityHashCode(), value is consecutive Ctrl-X count.
     */
    private static Map<Integer, Integer> ctrlXMap = null;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Static initializer sets up CRC table and Ctrl-X counts.
     */
    static {
        ctrlXMap = new HashMap<Integer, Integer>();
    }

    /**
     * Package private constructor.
     *
     * @param type header type
     * @param wireByte wire character for this header type
     * @param description description of header
     */
    Header(final Type type, final byte wireByte, final String description) {
        this.type               = type;
        this.wireByte           = wireByte;
        this.description        = description;
    }

    // ------------------------------------------------------------------------
    // Header -----------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Getter for type.
     *
     * @return type
     */
    public Type getType() {
        return type;
    }

    /**
     * Getter for wireByte.
     *
     * @return the byte used on the wire to denote this header
     * type
     */
    public byte getWireByte() {
        return wireByte;
    }

    /**
     * Getter for description.
     *
     * @return description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get a header type enum from the transmitted character.
     *
     * @param typeByte the character sent/to the wire
     * @return the header type
     * @throws ZmodemProtocolException if the header type is invalid
     */
    public static Type getHeaderType(final byte typeByte) throws ZmodemProtocolException {

        switch (typeByte) {
        case 0x00:
            return Type.ZRQINIT;
        case 0x01:
            return Type.ZRINIT;
        case 0x02:
            return Type.ZSINIT;
        case 0x03:
            return Type.ZACK;
        case 0x04:
            return Type.ZFILE;
        case 0x05:
            return Type.ZSKIP;
        case 0x06:
            return Type.ZNAK;
        case 0x07:
            return Type.ZABORT;
        case 0x08:
            return Type.ZFIN;
        case 0x09:
            return Type.ZRPOS;
        case 0x0A:
            return Type.ZDATA;
        case 0x0B:
            return Type.ZEOF;
        case 0x0C:
            return Type.ZFERR;
        case 0x0D:
            return Type.ZCRC;
        case 0x0E:
            return Type.ZCHALLENGE;
        case 0x0F:
            return Type.ZCOMPL;
        case 0x10:
            return Type.ZCAN;
        case 0x11:
            return Type.ZFREECNT;
        case 0x12:
            return Type.ZCOMMAND;
        default:
            // Protocol error
            throw new ZmodemProtocolException("Invalid type: " + typeByte);
        }
    }

    // ------------------------------------------------------------------------
    // Encoder/decoder --------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Reads the next byte of data from the input stream.
     *
     * @return the next byte of data, or -1 if there is no more data because
     * the end of the stream has been reached.
     * @throws IOException if an I/O error occurs
     * @throws ZmodemCancelledException if five Ctrl-X's are encountered in a
     * row
     */
    private static int readCheckCtrlX(final InputStream input) throws IOException, ZmodemCancelledException {

        Integer ctrlXKey = System.identityHashCode(input);
        Integer ctrlXCount = ctrlXMap.get(ctrlXKey);
        if (ctrlXCount == null) {
            ctrlXCount = 0;
        }
        int ch = input.read();
        if (ch == 0x18) {
            ctrlXCount++;
            if (ctrlXCount == 5) {
                throw new ZmodemCancelledException("5 Ctrl-X's seen");
            }
        }
        ctrlXMap.put(ctrlXKey, ctrlXCount);
        return ch;
    }

    /**
     * Reads some number of bytes from the input stream and stores them into
     * the buffer array b.
     *
     * @param b the buffer into which the data is read.
     * @return the total number of bytes read into the buffer, or -1 if there
     * is no more data because the end of the stream has been reached.
     * @throws IOException if an I/O error occurs
     * @throws ZmodemCancelledException if five Ctrl-X's are encountered in a
     * row
     */
    public static int readCheckCtrlC(final byte[] b,
        final InputStream input) throws IOException, ZmodemCancelledException {

        Integer ctrlXKey = System.identityHashCode(input);
        Integer ctrlXCount = ctrlXMap.get(ctrlXKey);
        if (ctrlXCount == null) {
            ctrlXCount = 0;
        }
        int rc = input.read(b);
        for (int i = 0; i < b.length; i++) {
            byte ch = b[i];
            if (ch == 0x18) {
                ctrlXCount++;
                if (ctrlXCount == 5) {
                    throw new ZmodemCancelledException("3 Ctrl-C's seen");
                }
            } else {
                ctrlXCount = 0;
            }
        }
        ctrlXMap.put(ctrlXKey, ctrlXCount);
        return rc;
    }

    /**
     * Encodes this header into a stream of bytes appropriate for
     * transmitting on the wire.
     *
     * @return encoded bytes
     * @throws IOException if a java.io operation throws
     */
    public byte [] encode() throws IOException {
        // TODO
        return null;
    }

    /**
     * Decode wire-encoded bytes into a header.
     *
     * @param input stream to read from
     * @return the next packet, mangled or not.  In insufficient data is
     * present to determine the correct packet type, a ZNak will be
     * returned.
     * @param zmodemState overall protocol state, used for some special cases
     * @throws IOException if a java.io operation throws
     */
    public static Header decode(final EOFInputStream input,
        final ZmodemState zmodemState) throws ReadTimeoutException,
                                              EOFException, IOException,
                                              ZmodemCancelledException {

        // TODO
        return null;
    }

}

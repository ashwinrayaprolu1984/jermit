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
import java.io.OutputStream;
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

    /**
     * XON, required to terminate some headers.  Note package private access.
     */
    static final byte C_XON = 0x11;

    /**
     * XOFF, required to be escaped.  Note package private access.
     */
    static final byte C_XOFF = 0x13;

    /**
     * CAN, used to escape bytes.  Note package private access.
     */
    static final byte C_CAN = 0x18;

    /**
     * ZPAD, used to start a header.
     */
    private static final byte ZPAD = '*';

    /**
     * CRC next, frame ends, header packet follows.  Note package private
     * access.
     */
    static final byte ZCRCE = 'h';

    /**
     * CRC next, frame continues nonstop.  Note package private access.
     */
    static final byte ZCRCG = 'i';

    /**
     * CRC next, frame continues, ZACK expected.  Note package private
     * access.
     */
    static final byte ZCRCQ = 'j';

    /**
     * CRC next, frame ends, ZACK expected.  Note package private access.
     */
    static final byte ZCRCW = 'k';

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
         * Header had a CRC error.
         */
        TRANSMIT_CRC,

        /**
         * Protocol error: ZCAN byte is wrong.
         */
        PROTO_ZCAN,

        /**
         * Protocol error: the header encoding type byte (hex, crc16, crc32)
         * is wrong.
         */
        PROTO_ENCODING_TYPE,

        /**
         * Protocol error: the type byte is wrong, there is no header with
         * this type.
         */
        PROTO_HEADER_TYPE,

        /**
         * Protocol error: invalid byte encoding.
         */
        PROTO_ENCODING,

        /**
         * Data subpacket had a CRC error.
         */
        TRANSMIT_DATA_CRC,

        /**
         * Data subpacket had invalid data in it.
         */
        PROTO_DATA_INVALID,
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
     *
     * Why static?  Because each Ctrl-X in the input returns a new ZNak in
     * decode(), thus a single Header instance can't see multiple Ctrl-X's to
     * count them.  We could also have this at session level, but that seems
     * like it could be more confusing in stack traces.
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
     * @param data the data field for this header
     */
    Header(final Type type, final byte wireByte, final String description,
        final int data) {
        this.type               = type;
        this.wireByte           = wireByte;
        this.description        = description;
        this.data               = data;
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
    private static int readCheckCtrlX(final EOFInputStream input) throws IOException, ZmodemCancelledException {

        Integer ctrlXKey = System.identityHashCode(input);
        Integer ctrlXCount = null;
        synchronized (ctrlXMap) {
            ctrlXCount = ctrlXMap.get(ctrlXKey);
        }
        if (ctrlXCount == null) {
            ctrlXCount = 0;
        }
        int ch = input.read();
        if (ch == 0x18) {
            ctrlXCount++;
            if (ctrlXCount == 5) {
                throw new ZmodemCancelledException("5 Ctrl-X's seen");
            }
        } else {
            ctrlXCount = 0;
        }
        synchronized (ctrlXMap) {
            ctrlXMap.put(ctrlXKey, ctrlXCount);
        }
        if (DEBUG) {
            System.err.printf(" %02x", ch);
        }
        return ch;
    }

    /**
     * Turn a byte array into a hex string.
     *
     * @param input a buffer with 8-bit bytes
     * @param len the number of bytes in input
     * @return the output bytes of hexademical ASCII characters
     */
    private byte [] toHexBytes(final byte [] input, int len) {

        byte [] digits = {'0', '1', '2', '3', '4', '5', '6', '7',
                          '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

        assert (len >= 0);

        byte [] result = new byte[len * 2];

        for (int i = 0; i < len; i++) {
            result[2 * i]     = digits[(input[i] & 0xF0) >>> 4];
            result[2 * i + 1] = digits[ input[i] & 0x0F       ];
        }
        return result;
    }

    /**
     * Turn a hex string into a byte array.
     *
     * @param input stream to read from
     * @param len the number of bytes in input
     * @return the output of 8-bit bytes
     * @throws NumberFormatException if the input bytes are not correct hex
     * @throws IOException if a java.io operation throws
     * @throws ZmodemCancelledException if five Ctrl-X's are encountered in a
     * row
     */
    private static byte [] fromHexBytes(final EOFInputStream input,
        int len) throws IOException, NumberFormatException,
                        ZmodemCancelledException {

        assert (len >= 0);
        assert ((len % 2) == 0);
        byte [] result = new byte[len / 2];

        for (int i = 0; i < len; i++) {
            int j = i / 2;

            int ch = readCheckCtrlX(input);
            if ((ch >= 'A') && (ch <= 'F')) {
                ch += ('a' - 'A');
            }
            if ((ch >= '0') && (ch <= '9')) {
                result[j] = (byte) (ch - '0');
            } else if ((ch >= 'a') && (ch <= 'f')) {
                result[j] = (byte) (ch - 'a' + 0x0A);
            } else {
                /*
                 * Invalid hex string
                 */
                throw new NumberFormatException("Invalid hex string at " +
                    "position " + i + ": " + ch);
            }
            result[j] = (byte) (result[j] << 4);

            i++;
            ch = readCheckCtrlX(input);
            if ((ch >= 'A') && (ch <= 'F')) {
                ch += ('a' - 'A');
            }
            if ((ch >= '0') && (ch <= '9')) {
                result[j] |= (byte) (ch - '0');
            } else if ((ch >= 'a') && (ch <= 'f')) {
                result[j] |= (byte) (ch - 'a' + 0x0A);
            } else {
                /*
                 * Invalid hex string
                 */
                throw new NumberFormatException("Invalid hex string at " +
                    "position " + i + ": " + ch);
            }
        }

        return result;
    }

    /**
     * Convert a 32-bit int from big endian to little endian.  Note package
     * private access.
     *
     * @param x the integer to convert
     * @return the integer in reverse-byte order
     */
    static int bigToLittleEndian(final int x) {
        return (((x >>> 24) & 0xFF) |
                ((x >>>  8) & 0xFF00) |
                ((x <<   8) & 0xFF0000) |
                ((x <<  24) & 0xFF000000));
    }

    /**
     * Turn one byte into 1 or 2 escaped bytes.
     *
     * @param ch the byte to convert
     * @param session the ZmodemSession
     * @return the encoded byte(s)
     */
    private byte [] encodeByte(final byte ch, final ZmodemSession session) {
        int newCh = ch;
        if (newCh < 0) {
            newCh = session.encodeByteMap[newCh + 256];
        } else {
            newCh = session.encodeByteMap[ch];
        }
        /*
        if (DEBUG) {
            System.err.printf("encodeByte() %02x --> %02x\n", ch, newCh);
        }
        */

        byte [] result;
        if (newCh != ch) {
            /*
             * Encode
             */
            result = new byte[2];
            result[0] = C_CAN;
            result[1] = (byte) (newCh & 0xFF);
        } else {
            /*
             * Regular character
             */
            result = new byte[1];
            result[0] = ch;
        }
        return result;
    }

    /**
     * Encodes this header into a stream of bytes appropriate for
     * transmitting on the wire.
     *
     * @param session the ZmodemSession
     * @param offset initial place in the ZDATA stream
     * @return encoded bytes
     * @throws IOException if a java.io operation throws
     */
    public byte [] encode(final ZmodemSession session,
        final long offset) throws IOException {

        if (DEBUG) {
            System.err.printf("encode(): type = %s (%d) data = %08x\n",
                getDescription(), wireByte, data);
        }

        byte [] header = new byte[10];
        boolean useCrc32 = session.useCrc32;
        boolean doHex = false;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        /*
         * Copy type to first header byte
         */
        header[0] = wireByte;

        switch (type) {

        case ZRPOS:
        case ZEOF:
        case ZCRC:
        case ZCOMPL:
        case ZFREECNT:
        case ZSINIT:
        case ZDATA:
            /*
             * Little endian order only for these types.
             */
            header[4] = (byte) ((data >>> 24) & 0xFF);
            header[3] = (byte) ((data >>> 16) & 0xFF);
            header[2] = (byte) ((data >>>  8) & 0xFF);
            header[1] = (byte) ( data         & 0xFF);
            break;
        default:
            /*
             * Everything else is in big endian order.
             */
            header[1] = (byte) ((data >>> 24) & 0xFF);
            header[2] = (byte) ((data >>> 16) & 0xFF);
            header[3] = (byte) ((data >>>  8) & 0xFF);
            header[4] = (byte) ( data         & 0xFF);
            break;
        }

        switch (type) {

        case ZRQINIT:
        case ZRINIT:
        case ZSINIT:
        case ZCHALLENGE:
            /*
             * ZCHALLENGE comes before the CRC32 negotiation, so it must use
             * hex packets.  The other packets are defined by the standard to
             * be hex.
             */
        case ZRPOS:

            doHex = true;
            break;

        default:
            if (session.escapeControlChars || session.escape8BitChars) {
                doHex = true;
            } else {
                doHex = false;
            }
            break;
        }

        /*
         * OK, so we can get seriously out of sync with rz -- it doesn't
         * bother checking to see if ZSINIT is CRC32 or not.  So we have to
         * see what it expects and encode appropriately.
         */
        if ((type == Type.ZSINIT)
            && (session.isDownload() == false)
            && (session.useCrc32 == true)
        ) {
            doHex = false;
        }

        /*
         * A bug in sz: it sometimes loses the ZCRC even though it reads the
         * bytes.
         */
        if ((type == Type.ZCRC) && (session.isDownload() == true)) {
            doHex = true;
        }

        if (doHex == true) {

            /*
             * Hex must be 16-bit CRC, override the default setting
             */
            useCrc32 = false;

            /*
             * Hex packets
             */
            output.write(ZPAD);
            output.write(ZPAD);
            output.write(C_CAN);
            output.write('B');
            output.write(toHexBytes(header, 5));

            /*
             * Hex packets always use 16-bit CRC
             */
            int crc16 = Crc.computeCrc16(0, header, 5);
            byte [] crc16Hex = new byte[2];
            crc16Hex[0] = (byte) ((crc16 >>> 8) & 0xFF);
            crc16Hex[1] = (byte) ( crc16        & 0xFF);
            output.write(toHexBytes(crc16Hex, 2));
            output.write(C_CR);

            /*
             * lrzsz flips the high bit here.  Why??
             */
            /* output.write(C_LF); */
            output.write(C_LF | 0x80);

            switch (type) {
            case ZFIN:
            case ZACK:
                break;
            default:
                /*
                 * Append XON to most hex headers
                 */
                output.write(C_XON);
                break;
            }

        } else {
            boolean alteredEncodeByteMap = false;
            boolean oldEscapeControlChars = session.escapeControlChars;

            if (type == Type.ZSINIT) {
                /*
                 * Special case: lrzsz needs control characters escaped in the
                 * ZSINIT.
                 */
                if (session.escapeControlChars == false) {
                    alteredEncodeByteMap = true;

                    /*
                     * Update the encode map
                     */
                    session.escapeControlChars = true;
                    session.setupEncodeByteMap();
                }
            }

            /*
             * Binary headers
             */
            output.write(ZPAD);
            output.write(C_CAN);
            if (useCrc32 == true) {
                output.write('C');
            } else {
                output.write('A');
            }

            /*
             * Encode the argument field
             */
            for (int i = 0; i < 5; i++) {
                output.write(encodeByte(header[i], session));
            }

            if (useCrc32 == true) {
                int crc32 = Crc.computeCrc32(0, null, 0);
                crc32 = Crc.computeCrc32(crc32, header, 5);
                /*
                 * Little-endian
                 */
                output.write(encodeByte((byte) ( crc32         & 0xFF),
                        session));
                output.write(encodeByte((byte) ((crc32 >>>  8) & 0xFF),
                        session));
                output.write(encodeByte((byte) ((crc32 >>> 16) & 0xFF),
                        session));
                output.write(encodeByte((byte) ((crc32 >>> 24) & 0xFF),
                        session));
            } else {
                int crc16 = Crc.computeCrc16(0, header, 5);
                output.write(encodeByte((byte) ((crc16 >>>  8) & 0xFF),
                        session));
                output.write(encodeByte((byte) ( crc16         & 0xFF),
                        session));
            }

            if (alteredEncodeByteMap == true) {
                /*
                 * Restore encode_byte_map and flags
                 */
                session.escapeControlChars = oldEscapeControlChars;
                session.setupEncodeByteMap();
            }
        }

        /*
         * Write data subpacket but only for certain header types.
         */
        switch (type) {
        case ZSINIT:
        case ZFILE:
        case ZDATA:
        case ZCOMMAND:
            /*
             * Data "subpacket" follows, write it.
             */
            writeDataSubpacket(session, output, useCrc32, offset);
            break;
        default:
            break;
        }


        /*
         * All done.
         */
        return output.toByteArray();
    }

    /**
     * Decode wire-encoded bytes into a header.
     *
     * @param session the ZmodemSession
     * @param input stream to read from
     * @param offset initial place in the ZDATA stream
     * @return the next header, mangled or not.  In insufficient data is
     * present to determine the correct header type, a ZNak will be returned.
     * @throws IOException if a java.io operation throws
     * @throws ZmodemCancelledException if five Ctrl-X's are encountered in a
     * row
     */
    public static Header decode(final ZmodemSession session,
        final EOFInputStream input,
        final long offset) throws EOFException, IOException,
                                  ReadTimeoutException,
                                  ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("decode()");
        }

        /*
         * Find the start of the header
         */
        int ch = readCheckCtrlX(input);
        while (ch != ZPAD) {
            ch = readCheckCtrlX(input);
        }
        while (ch == ZPAD) {
            ch = readCheckCtrlX(input);
        }
        if (ch != C_CAN) {
            return new ZNak(ParseState.PROTO_ZCAN);
        }
        ch = readCheckCtrlX(input);
        if ((ch != 'A') && (ch != 'B') && (ch != 'C')) {
            return new ZNak(ParseState.PROTO_ENCODING_TYPE);
        }

        boolean gotCan = false;
        ByteArrayOutputStream crcBuffer = new ByteArrayOutputStream();
        byte typeWireByte = 0;
        boolean useCrc32 = false;
        int givenCrc16 = 0;
        int givenCrc32 = 0;
        int dataField = 0;

        if (ch == 'A') {
            /*
             * CRC-16
             */

            /*
             * Loop through the type, argument, and crc values, unescaping
             * control characters along the way.
             */
            for (int i = 0; i < 7; i++) {
                ch = readCheckCtrlX(input);
                if (ch == C_CAN) {
                    /*
                     * Escape control char
                     */
                    gotCan = true;
                    i--;
                    continue;
                }

                if (gotCan == true) {
                    gotCan = false;
                    if (ch == 'l') {
                        /*
                         * Escaped control character: 0x7f
                         */
                        ch = 0x7F;
                    } else if (ch == 'm') {
                        /*
                         * Escaped control character: 0xff
                         */
                        ch = 0xFF;
                    } else if ((ch & 0x40) != 0) {
                        /*
                         * Escaped control character: CAN m OR 0x40
                         */
                        ch = ch & 0xBF;
                    } else {
                        /*
                         * Should never get here
                         */
                        return new ZNak(ParseState.PROTO_ENCODING);
                    }
                }

                if (i == 0) {
                    /*
                     * Type
                     */
                    typeWireByte = (byte) ch;
                    crcBuffer.write(ch);
                } else if (i < 5) {
                    /*
                     * Argument
                     */
                    dataField |= (ch << (32 - (8 * i)));
                    crcBuffer.write(ch);
                } else {
                    /*
                     * CRC
                     */
                    givenCrc16 |= (ch << (16 - (8 * (i - 4))));
                }
            }

        } else if (ch == 'B') {

            /*
             * CRC-16 HEX
             */
            byte [] crcGiven = null;

            try {
                /*
                 * Dehexify
                 */
                typeWireByte = fromHexBytes(input, 2)[0];
                byte [] argument = fromHexBytes(input, 8);
                dataField = ((argument[0] & 0xFF) << 24) |
                            ((argument[1] & 0xFF) << 16) |
                            ((argument[2] & 0xFF) <<  8) |
                            ( argument[3] & 0xFF);

                /*
                 * Copy header to crcBuffer
                 */
                crcBuffer.write(typeWireByte);
                crcBuffer.write(argument);

                crcGiven = fromHexBytes(input, 4);
                givenCrc16 = ((crcGiven[0] & 0xFF) << 8) |
                             ( crcGiven[1] & 0xFF);
            } catch (NumberFormatException e) {
                return new ZNak(ParseState.PROTO_ENCODING);
            }

            /*
             * More special-case junk: sz sends 0d 8a at the end of each hex
             * header.
             */
            ch = readCheckCtrlX(input);
            if (ch != 0x0d) {
                if (DEBUG) {
                    System.err.println("Did not get expected 0x0d");
                }
            }
            ch = readCheckCtrlX(input);
            if (ch != 0x8a) {
                if (DEBUG) {
                    System.err.println("Did not get expected 0x8a");
                }
            }

            /*
             * sz also sends XON at the end of each hex header except ZFIN
             * and ZACK.
             */
            switch (typeWireByte) {
            case 3:     // ZACK
            case 8:     // ZFIN
                break;
            default:
                ch = readCheckCtrlX(input);
                if (ch != C_XON) {
                    if (DEBUG) {
                        System.err.println("Did not get expected XON");
                    }
                }
                break;
            }

        } else if (ch == 'C') {
            /*
             * CRC-32
             */
            useCrc32 = true;

            /*
             * Loop through the type, argument, and crc values, unescaping
             * control characters along the way.
             */
            for (int i = 0; i < 9; i++) {
                ch = readCheckCtrlX(input);
                if (ch == C_CAN) {
                    /*
                     * Escape control char
                     */
                    gotCan = true;
                    i--;
                    continue;
                }

                if (gotCan == true) {
                    gotCan = false;
                    if (ch == 'l') {
                        /*
                         * Escaped control character: 0x7f
                         */
                        ch = 0x7F;
                    } else if (ch == 'm') {
                        /*
                         * Escaped control character: 0xff
                         */
                        ch = 0xFF;
                    } else if ((ch & 0x40) != 0) {
                        /*
                         * Escaped control character: CAN m OR 0x40
                         */
                        ch = ch & 0xBF;
                    } else {
                        /*
                         * Should never get here
                         */
                        return new ZNak(ParseState.PROTO_ENCODING);
                    }
                }

                if (i == 0) {
                    /*
                     * Type
                     */
                    typeWireByte = (byte) ch;
                    crcBuffer.write(ch);
                } else if (i < 5) {
                    /*
                     * Argument
                     */
                    dataField |= (ch << (32 - (8 * i)));
                    crcBuffer.write(ch);
                } else {
                    /*
                     * CRC - in little-endian form
                     */
                    givenCrc32 |= (ch << (8 * (i - 5)));
                }

            }

        } else {
            /*
             * Invalid header encoding type, but we should never get here
             * because we have already checked to ensure that the encoding
             * was supported.
             */
            throw new RuntimeException("BUG: should never get here!");
        }

        /*
         * Construct an appropriate Header based on the type byte.
         */
        Header header = null;
        switch (typeWireByte) {
        case 0x00:
            header = new ZRQInit(dataField);
            break;
        case 0x01:
            header = new ZRInit(dataField);
            break;
        case 0x02:
            header = new ZSInit(dataField);
            break;
        case 0x03:
            header = new ZAck(dataField);
            break;
        case 0x04:
            header = new ZFile(dataField);
            break;
        case 0x05:
            header = new ZSkip(dataField);
            break;
        case 0x06:
            header = new ZNak(dataField);
            break;
        case 0x07:
            header = new ZAbort(dataField);
            break;
        case 0x08:
            header = new ZFin(dataField);
            break;
        case 0x09:
            header = new ZRPos(dataField);
            break;
        case 0x0A:
            header = new ZData(dataField);
            break;
        case 0x0B:
            header = new ZEof(dataField);
            break;
        /*
        case 0x0C:
            type_string = "ZFERR";
            break;
        case 0x0D:
            type_string = "ZCRC";
            break;
        */
        case 0x0E:
            header = new ZChallenge(dataField);
            break;
        /*
        case 0x0F:
            type_string = "ZCOMPL";
            break;
        case 0x10:
            type_string = "ZCAN";
            break;
        case 0x11:
            type_string = "ZFREECNT";
            break;
        case 0x12:
            type_string = "ZCOMMAND";
            break;
         */
        default:
            if (DEBUG) {
                System.err.printf("\ndecode(): INVALID HEADER TYPE %d\n",
                    typeWireByte);
            }
            return new ZNak(ParseState.PROTO_HEADER_TYPE);
        }

        /*
         * Figure out if the argument is supposed to be flipped
         */
        switch (header.type) {

        case ZRPOS:
        case ZEOF:
        case ZCRC:
        case ZCOMPL:
        case ZFREECNT:
        case ZDATA:
            /*
             * Swap the data argument around
             */
            header.data = bigToLittleEndian(header.data);
            break;
        default:
            break;
        }

        if (DEBUG) {
            if (useCrc32 == true) {
                System.err.printf("\ndecode(): CRC32 type = %s (%d) " +
                    "argument=%08x crc=%08x\n", header.getDescription(),
                    header.getWireByte(), header.data, givenCrc32);
            } else {
                System.err.printf("\ndecode(): CRC16 type = %s (%d) " +
                    "argument=%08x crc=%04x\n", header.getDescription(),
                    header.getWireByte(), header.data, givenCrc16);
            }
        }

        /*
         * Check CRC
         */
        if (useCrc32 == true) {
            int crc32 = Crc.computeCrc32(0, null, 0);
            crc32 = Crc.computeCrc32(crc32, crcBuffer.toByteArray(), 5);
            if (crc32 == givenCrc32) {
                if (DEBUG) {
                    System.err.println("decode(): CRC OK");
                }
            } else {
                if (DEBUG) {
                    System.err.printf("decode(): CRC ERROR " +
                        "(given=%08x computed=%08x)\n",
                        givenCrc32, crc32);
                }
                header.parseState = ParseState.TRANSMIT_CRC;
            }

        } else {
            int crc16 = Crc.computeCrc16(0, crcBuffer.toByteArray(), 5);
            if (crc16 == givenCrc16) {
                if (DEBUG) {
                    System.err.println("decode(): CRC OK");
                }
            } else {
                if (DEBUG) {
                    System.err.printf("decode(): CRC ERROR " +
                        "(given=%04x computed=%04x)\n",
                        givenCrc16, crc16);
                }
                header.parseState = ParseState.TRANSMIT_CRC;
            }

        }

        /*
         * Pull data subpacket but only for certain header types.
         */
        switch (header.type) {
        case ZSINIT:
        case ZFILE:
        case ZDATA:
        case ZCOMMAND:
            /*
             * Data "subpacket" follows, read it.
             */
            if (header.readDataSubpacket(session, input, useCrc32,
                    offset) == false) {
                header.parseState = ParseState.TRANSMIT_DATA_CRC;
            }
            break;
        default:
            break;
        }

        /*
         * All OK
         */
        return header;
    }

    /**
     * Parse whatever came in a data subpacket.  Used by subclasses to put
     * data into fields.
     *
     * @param subpacket the bytes of the subpacket
     */
    protected void parseDataSubpacket(final byte [] subpacket) {}

    /**
     * Read a data subpacket and then call parseDataSubpacket.
     *
     * @param session the ZmodemSession
     * @param input stream to read from
     * @param useCrc32 if true, this is a 32-bit CRC
     * @param offset initial place in the ZDATA stream
     * @return true if the data subpacket had a good CRC
     * @throws IOException if a java.io operation throws
     * @throws ZmodemCancelledException if five Ctrl-X's are encountered in a
     * row
     */
    private boolean readDataSubpacket(final ZmodemSession session,
        final EOFInputStream input, final boolean useCrc32,
        final long offset) throws IOException, ZmodemCancelledException {

        if (DEBUG) {
            System.err.printf("readDataSubpacket() %s\n",
                (useCrc32 ? "CRC32" : "CRC16"));
        }

        ByteArrayOutputStream finalResult = new ByteArrayOutputStream();
        long currentOffset = offset;
        boolean endHeader = false;
        while (!endHeader) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            ByteArrayOutputStream crcBuffer = new ByteArrayOutputStream();
            boolean doingCrc = false;
            boolean done = false;
            byte crcType = 0;
            int ch = -1;
            boolean needAck = false;
            while (!done) {

                ch = readCheckCtrlX(input);
                if (ch == C_CAN) {
                    /*
                     * Entered a CRC escape.  Point past the CAN.
                     */
                    ch = readCheckCtrlX(input);

                    if ((ch == ZCRCE)
                        || (ch == ZCRCG)
                        || (ch == ZCRCQ)
                        || (ch == ZCRCW)
                    ) {
                        if (doingCrc == true) {
                            /*
                             * CRC escape within a CRC escape.  This is a
                             * protocol error or bad data on the wire.
                             */
                            return false;
                        }
                        /*
                         * CRC escape, switch to crc collection
                         */
                        doingCrc = true;
                        crcType = (byte) ch;
                        assert (crcBuffer.size() == 0);
                        crcBuffer.write(ch);
                    } else if (ch == 'l') {
                        /*
                         * Escaped control character: 0x7f
                         */
                        if (doingCrc == true) {
                            crcBuffer.write(0x7F);
                        } else {
                            result.write(0x7F);
                        }
                    } else if (ch == 'm') {
                        /*
                         * Escaped control character: 0xff
                         */
                        if (doingCrc == true) {
                            crcBuffer.write(0xFF);
                        } else {
                            result.write(0xFF);
                        }
                    } else if ((ch & 0x40) != 0) {
                        /*
                         * Escaped control character: CAN m OR 0x40
                         */
                        if (doingCrc == true) {
                            crcBuffer.write(ch & 0xBF);
                        } else {
                            result.write(ch & 0xBF);
                        }
                    } else {
                        /*
                         * Should never get here.  This is a protocol error or
                         * bad data on the wire.
                         */
                    }

                } else {
                    /*
                     * If we're doing the CRC part, put the data elsewhere.
                     */
                    if (doingCrc == true) {
                        crcBuffer.write(ch);
                    } else {
                        /*
                         * I ought to ignore any unencoded control characters
                         * when encoding was requested at this point here.
                         * However, encoding control characters is broken anyway
                         * in lrzsz so I won't bother with a further check.  If
                         * you want actually reliable transfer over
                         * not-8-bit-clean links, use Kermit instead.
                         */
                        result.write(ch);
                    }

                }

                if (doingCrc == true) {
                    if ((useCrc32 == true) && (crcBuffer.size() == 5)) {
                        /*
                         * Done
                         */
                        done = true;
                    } else if ((useCrc32 == false) && (crcBuffer.size() == 3)) {
                        /*
                         * Done
                         */
                        done = true;
                    }
                }

            } // while (!done)

            switch (crcType) {

            case ZCRCE:
                /*
                 * CRC next, frame ends, header packet follows
                 */
                if (DEBUG) {
                    System.err.println("\nreadDataSubpacket(): ZCRCE");
                }
                endHeader = true;
                break;
            case ZCRCG:
                /*
                 * CRC next, frame continues nonstop
                 */
                if (DEBUG) {
                    System.err.println("\nreadDataSubpacket(): ZCRCG");
                }
                break;
            case ZCRCQ:
                /*
                 * CRC next, frame continues, ZACK expected
                 */
                if (DEBUG) {
                    System.err.println("\nreadDataSubpacket(): ZCRCQ");
                }
                needAck = true;
                break;
            case ZCRCW:
                /*
                 * CRC next, ZACK expected, end of frame
                 */
                if (DEBUG) {
                    System.err.println("\nreadDataSubpacket(): ZCRCW");
                }
                needAck = true;
                endHeader = true;
                break;
            default:
                /*
                 * Unknown CRC type, bail out.
                 */
                if (DEBUG) {
                    System.err.println("\nreadDataSubpacket(): " +
                        "UNKNOWN CRC TYPE !!!");
                }
                return false;
            }

            if (crcType == ZCRCW) {
                /*
                 * ZCRCW is supposed to always followed by XON.  For sanity
                 * see if that was actually the case or not.
                 */
                ch = readCheckCtrlX(input);
                if (DEBUG) {
                    System.err.printf("\nZCRCW XON was: %s\n",
                        (ch == C_XON ? "present" : "absent"));
                }
            }

            /*
             * Check the CRC now.  Notice in the checks below that the CRC
             * escape byte itself has to be included in the CRC check.  Yes,
             * this is stupid.
             *
             * If the CRC fails, return false.
             */
            byte [] packetBytes = result.toByteArray();
            byte [] crcBytes = crcBuffer.toByteArray();
            byte [] crcInput = new byte[packetBytes.length + 1];
            System.arraycopy(packetBytes, 0, crcInput, 0, packetBytes.length);
            crcInput[packetBytes.length] = crcBytes[0];

            if (useCrc32 == true) {
                int crc32 = Crc.computeCrc32(0, null, 0);
                crc32 = Crc.computeCrc32(crc32, crcInput, crcInput.length);
                /*
                 * Little-endian
                 */
                int givenCrc32 = ((crcBytes[4] & 0xFF) << 24) |
                                 ((crcBytes[3] & 0xFF) << 16) |
                                 ((crcBytes[2] & 0xFF) << 8) |
                                  (crcBytes[1] & 0xFF);

                if (DEBUG) {
                    System.err.printf("readDataSubpacket(): " +
                        "DATA CRC32: given    %08x\n", givenCrc32);
                    System.err.printf("readDataSubpacket(): " +
                        "DATA CRC32: computed %08x\n", crc32);
                }

                if (crc32 != givenCrc32) {
                    return false;
                }
            } else {
                int crc16 = Crc.computeCrc16(0, crcInput, crcInput.length);
                int givenCrc16 = ((crcBytes[1] & 0xFF) << 8) |
                                  (crcBytes[2] & 0xFF);

                if (DEBUG) {
                    System.err.printf("readDataSubpacket(): " +
                        "DATA CRC16: given    %04x\n", givenCrc16);
                    System.err.printf("readDataSubpacket(): " +
                        "DATA CRC16: computed %04x\n", crc16);
                }

                if (crc16 != givenCrc16) {
                    return false;
                }
            }
            currentOffset += packetBytes.length;
            if (needAck) {
                switch (session.zmodemState) {
                case ZRPOS_WAIT:
                    session.sendHeader(new ZAck(
                        bigToLittleEndian((int) currentOffset)));
                    break;
                default:
                    break;
                }
            }
            finalResult.write(packetBytes);

            if (DEBUG) {
                System.err.printf("readDataSubpacket() %d bytes CRC OK\n",
                    packetBytes.length);
            }
        } /* while (!endHeader) */

        /*
         * All is good: we have data and a valid CRC.  Let the subclass parse
         * data and then return.
         */
        parseDataSubpacket(finalResult.toByteArray());
        return true;
    }

    /**
     * Get the data subpacket raw bytes.  Used by subclasses to serialize
     * fields into data.
     *
     * @return the bytes of the subpacket
     */
    protected byte [] createDataSubpacket() {
        return new byte[0];
    }

    /**
     * Call createDataSubpacket() and then encode it.
     *
     * @param session the ZmodemSession
     * @param output stream to write to
     * @param useCrc32 if true, write a 32-bit CRC
     * @param offset initial place in the ZDATA stream
     * @throws IOException if a java.io operation throws
     */
    private void writeDataSubpacket(final ZmodemSession session,
        final OutputStream output, final boolean useCrc32,
        final long offset) throws IOException {

        if (DEBUG) {
            System.err.printf("writeDataSubpacket() %s\n",
                (useCrc32 ? "CRC32" : "CRC16"));
        }

        byte [] rawData = createDataSubpacket();
        byte [] rawDataCrc = new byte[rawData.length + 1];
        System.arraycopy(rawData, 0, rawDataCrc, 0, rawData.length);
        rawDataCrc[rawData.length] = session.crcType;

        /*
         * Compute the CRC
         */
        ByteArrayOutputStream crcBuffer = new ByteArrayOutputStream();
        if ((useCrc32 == true) /* && (type != Type.ZSINIT) */ ) {
            int crc32 = Crc.computeCrc32(0, null, 0);
            crc32 = Crc.computeCrc32(crc32, rawDataCrc, rawDataCrc.length);

            if (DEBUG) {
                System.err.printf("writeDataSubpacket(): " +
                    "DATA CRC32: %08x\n", crc32);
            }

            /*
             * Little-endian
             */
            crcBuffer.write( crc32         & 0xFF);
            crcBuffer.write((crc32 >>>  8) & 0xFF);
            crcBuffer.write((crc32 >>> 16) & 0xFF);
            crcBuffer.write((crc32 >>> 24) & 0xFF);

        } else {
            int crc16 = Crc.computeCrc16(0, rawDataCrc, rawDataCrc.length);

            if (DEBUG) {
                System.err.printf("writeDataSubpacket(): " +
                    "DATA CRC16: %04x\n", crc16);
            }

            /*
             * Big-endian
             */
            crcBuffer.write((crc16 >>> 8) & 0xFF);
            crcBuffer.write( crc16        & 0xFF);
        }
        byte [] crcBytes = crcBuffer.toByteArray();
        for (int i = 0; i < rawData.length; i++) {
            byte [] ch = encodeByte(rawData[i], session);
            output.write(ch);
        }
        output.write(C_CAN);
        output.write(session.crcType);
        for (int i = 0; i < crcBytes.length; i++) {
            byte [] ch = encodeByte(crcBytes[i], session);
            output.write(ch);
        }

        /*
         * One type of packet is terminated "special"
         */
        if (session.crcType == ZCRCW) {
            output.write(C_XON);
        }
    }

}

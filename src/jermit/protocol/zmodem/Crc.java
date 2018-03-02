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
 * Functions for computing the 16-bit and 32-bit Zmodem CRC functions.
 *
 *
 * The following CRC32 code was posted by Colin Plumb in
 * comp.os.linux.development.system.  Google link:
 * http://groups.google.com/groups?selm=4dr0ab%24o1k%40nyx10.cs.du.edu
 *
 *
 *
 * This uses the CRC-32 from IEEE 802 and the FDDI MAC,
 * x^32+x^26+x^23+x^22+x^16+x^12+x^11+x^10+x^8+x^7+x^5+x^4+x^2+x+1.
 *
 * This is, for a slight efficiency win, used in a little-endian bit order,
 * where the least significant bit of the accumulator (and each byte of
 * input) corresponds to the highest exponent of x.  E.g. the byte 0x23 is
 * interpreted as x^7+x^6+x^2.  For the most rational output, the computed
 * 32-bit CRC word should be sent with the low byte (which are the most
 * significant coefficients from the polynomial) first.  If you do this, the
 * CRC of a buffer that includes a trailing CRC will always be zero.  Or, if
 * you use a trailing invert variation, some fixed value.  For this
 * polynomial, that fixed value is 0x2144df1c.  (Leading presets, be they to
 * 0, -1, or something else, don't matter.)
 *
 * Thus, the little-endian hex constant is as follows:
 *           11111111112222222222333
 * 012345678901234567890123456789012
 * 111011011011100010000011001000001
 * \  /\  /\  /\  /\  /\  /\  /\  /\
 *   E   D   B   8   8   3   2   0
 *
 * This technique, while a bit confusing, is widely used in e.g. Zmodem.
 *
 */
class Crc {

    // ------------------------------------------------------------------------
    // Constants --------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * CRC polynomial.
     */
    private static final int CRC32 = 0xedb88320;

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    private static int [] crc32Table = new int[256];

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Static initializer sets up CRC table and Ctrl-X counts.
     */
    static {
        makeCrc32Table();
    }

    // ------------------------------------------------------------------------
    // Crc32 ------------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Generate the CRC table.  From Colin Plumb's post:
     *
     * This computes the CRC table quite efficiently, using the fact that
     * crc_32_tab[i^j] = crc_32_tab[i] ^ crc_32_tab[j].  We start out with
     * crc_32_tab[0] = 0, j = 128, and then set h to the desired value of
     * crc_32_tab[j].  Then for each crc_32_tab[i] which is already set
     * (which includes i = 0, so crc_32_tab[j] will get set), set
     * crc_32_tab[i^j] = crc_32_tab[i] ^ h.  Then divide j by 2 and repeat
     * until everything is filled in.  The first pass sets crc_32_tab[128].
     * The second sets crc_32_tab[64] and crc_32_tab[192].  The third sets
     * entries 32, 96, 160 and 224.  The eighth and last pass sets all the
     * odd-numbered entries.
     */
    private static void makeCrc32Table() {
        int i, j = 128;
        int h = 1;

        crc32Table[0] = 0;
        do {
            if ((h & 1) != 0) {
                h = (h >> 1) ^ CRC32;
            } else {
                h >>= 1;
            }
            for (i = 0; i < 256; i += j + j) {
                crc32Table[i + j] = crc32Table[i] ^ h;
            }
        } while ((j >>= 1) != 0);
    }

    /**
     * Compute a CRC on the given buffer and length using a static CRC
     * accumulator.  If buf is NULL this initializes the accumulator,
     * otherwise it updates it to include the additional data and
     * returns the CRC of the data so far.
     *
     * The CRC is computed using preset to -1 and invert.
     *
     * @param oldCrc the old CRC value
     * @param buf the bytes to add to the CRC
     */
    public static int computeCrc32(final int oldCrc, final byte [] buf) {
        int crc;
        int i = 0;
        if (buf.length > 0) {
            crc = oldCrc;
            while (i < buf.length) {
                crc = (crc >> 8) ^ crc32Table[(crc ^ buf[i]) & 0xff];
                i++;
            }
            return crc ^ 0xffffffff;        /* Invert */
        } else {
            return 0xffffffff;      /* Preset to -1 */
        }
    }

    /**
     * This CRC16 routine was transliterated from XYMODEM.DOC.
     * 
     * @param oldCrc the old CRC
     * @param buf the message block
     * @return an integer which contains the CRC
     */
    public static int computeCrc16(final int oldCrc, final byte [] buf) {
        int crc = oldCrc;
        for (int i = 0; i < buf.length; i++) {
            crc = crc ^ (buf[i] << 8);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc = crc << 1;
                }
            }
        }
        return (crc & 0xFFFF);
    }

}

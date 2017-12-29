/*
 * Jermit
 *
 * The MIT License (MIT)
 *
 * Copyright (C) 2017 Kevin Lamonte
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
package jermit.protocol.kermit;

/**
 * BreakPacket is used to terminate a Kermit session.
 */
class BreakPacket extends Packet {

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Public constructor.
     *
     * @param checkType checksum type
     * @param seq sequence number of the packet
     */
    public BreakPacket(final byte checkType, final int seq) {
	super(Type.BREAK, (byte) 'B', "EOT Break Transmission", checkType, seq);
    }

    // ------------------------------------------------------------------------
    // Packet -----------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Decode data bytes into object fields.
     *
     * @throws KermitProtocolException if the other side violates the Kermit
     * protocol specification
     */
    @Override
    protected void readFromData() throws KermitProtocolException {
        // There are no object fields for this.  Higher-level code will look
        // at data[] directly.
    }

    /**
     * Encode object fields into data bytes.
     */
    @Override
    protected void writeToData() {
        // There are no object fields for this.  Higher-level code will look
        // at data[] directly.
    }

}

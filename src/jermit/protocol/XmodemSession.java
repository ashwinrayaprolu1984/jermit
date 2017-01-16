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
package jermit.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.LinkedList;

import jermit.io.EOFInputStream;
import jermit.io.LocalFileInterface;
import jermit.io.ReadTimeoutException;

/**
 * XmodemSession encapsulates all the state used by an upload or download
 * using the Xmodem protocol.
 */
public class XmodemSession extends SerialFileTransferSession {

    // If true, enable some debugging output.
    private static final boolean DEBUG = false;

    /**
     * The NAK byte used to request a packet repeat.
     */
    public static final byte NAK = 0x15;

    /**
     * The ACK byte used to acknowledge an OK packet.
     */
    public static final byte ACK = 0x06;

    /**
     * The SOH byte used to flag a 128-byte block.
     */
    public static final byte SOH = 0x01;

    /**
     * The STX byte used to flag a 1024-byte block.
     */
    public static final byte STX = 0x02;

    /**
     * The EOT byte used to end a transfer.
     */
    public static final byte EOT = 0x04;

    /**
     * The CAN byte used to forcefully terminate a transfer.
     */
    public static final byte CAN = 0x18;

    /**
     * Xmodem supports several variants.  These constants can be used to
     * select among them.
     */
    public enum Flavor {
        /**
         * Vanilla Xmodem: 128 byte blocks, checksum, 10-second timeout.
         */
        VANILLA,

        /**
         * Xmodem Relaxed: 128 byte blocks, checksum, 100-second timeout.
         */
        RELAXED,

        /**
         * Xmodem-CRC: 128 byte blocks, a 16-bit CRC, 10-second timeout.
         */
        CRC,

        /**
         * Xmodem-1k: 1024 byte blocks, a 16-bit CRC, 10-second timeout.
         */
        X_1K,

        /**
         * Xmodem-1k/G: 1024 byte blocks, a 16-bit CRC, 10-second timeout, no
         * ACKs.
         */
        X_1K_G,
    }

    /**
     * The type of Xmodem transfer to perform.
     */
    private Flavor flavor = Flavor.VANILLA;

    /**
     * Get the type of Xmodem transfer to perform.
     *
     * @return the Xmodem flavor
     */
    public Flavor getFlavor() {
        return flavor;
    }

    /**
     * Set the type of Xmodem transfer to perform.
     *
     * @param flavor the Xmodem flavor
     */
    public void setFlavor(Flavor flavor) {
        this.flavor = flavor;
    }

    /**
     * Get the protocol name.  Each protocol can have several variants.
     *
     * @return the protocol name for this transfer
     */
    public String getProtocolName() {
        switch (flavor) {
        case VANILLA:
            return "Xmodem";
        case RELAXED:
            return "Xmodem Relaxed";
        case CRC:
            return "Xmodem/CRC";
        case X_1K:
            return "Xmodem-1K";
        case X_1K_G:
            return "Xmodem-1K/G";
        }

        // Should never get here.
        throw new IllegalArgumentException("Xmodem flavor is not set " +
            "correctly");

    }

    /**
     * Get the block size.  Each protocol can have several variants.
     *
     * @return the block size
     */
    public int getBlockSize() {
        if ((flavor == Flavor.X_1K) || (flavor == Flavor.X_1K_G)) {
            return 1024;
        }
        return 128;
    }

    /**
     * The current sequence number.  Note package private access.
     */
    int sequenceNumber = 1;

    /**
     * The number of consecutive errors.  After 10 errors, the transfer is
     * cancelled.  Note package private access.
     */
    int consecutiveErrors = 0;

    /**
     * Xmodem CRC routine was transliterated from XYMODEM.DOC.
     *
     * @param data the data bytes to perform the CRC against
     * @return the 16-bit CRC
     */
    private int crc16(byte [] data) {
        int crc = 0;
        for (int i = 0; i < data.length; i++) {
            crc = crc ^ (((int) data[i]) << 8);
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

    /**
     * Trim the CPM EOF byte (0x1A) from the end of a file.
     *
     * @param filename the name of the file to trim on the local filesystem
     */
    public void trimEOF(final String filename) {
        try {
            // SetLength() requires the file be open in read-write.
            RandomAccessFile contents = new RandomAccessFile(filename, "rw");
            while (contents.length() > 0) {
                contents.seek(contents.length() - 1);
                int ch = contents.read();
                if (ch == 0x1A) {
                    contents.setLength(contents.length() - 1);
                } else {
                    // Found a non-EOF byte
                    break;
                }
            }
        } catch (IOException e) {
            if (DEBUG) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Count a timeout, cancelling the transfer if there are too many
     * consecutive errors.  Note package private access.
     *
     * @param output the stream to write to
     * @throws IOException if a java.io operation throws
     */
    void timeout(final OutputStream output) throws IOException {

        if (DEBUG) {
            System.err.println("TIMEOUT");
        }
        addErrorMessage("TIMEOUT");

        consecutiveErrors++;
        if (consecutiveErrors == 10) {
            // Cancel this transfer.
            abort(output);
            return;
        }
    }

    /**
     * Purge the input stream and send NAK to the remote side.
     *
     * @param input the stream to read from
     * @param output the stream to write to
     * @throws IOException if a java.io operation throws
     */
    private void purge(final InputStream input,
        final OutputStream output) throws IOException {

        if (DEBUG) {
            System.err.println("PURGE");
        }

        // Purge whatever is there, and try it all again.
        input.skip(input.available());

        consecutiveErrors++;
        if (consecutiveErrors == 10) {
            // Cancel this transfer.
            abort(output);
            return;
        }

        // Send NAK
        if (DEBUG) {
            System.err.println("NAK " + bytesTransferred);
        }

        output.write(NAK);
        output.flush();
    }

    /**
     * Ack the packet.
     *
     * @param output the stream to write to
     * @throws IOException if a java.io operation throws
     */
    private void ack(final OutputStream output) throws IOException {

        if (DEBUG) {
            System.err.println("ACK");
        }

        // Send ACK
        output.write(ACK);
        output.flush();
    }

    /**
     * Abort the transfer.  Note package private access.
     *
     * @param output the stream to write to
     * @throws IOException if a java.io operation throws
     */
    synchronized void abort(final OutputStream output) {

        if (DEBUG) {
            System.err.println("ABORT");
        }

        state = State.ABORT;

        // Send CAN, squashing any errors
        try {
            output.write(CAN);
            output.flush();
        } catch (IOException e) {
            // SQUASH
            if (DEBUG) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Read one Xmodem packet from the stream.
     *
     * @param input the stream to read from
     * @param output the stream to write to
     * @return the raw bytes of the file contents
     * @throws IOException if a java.io operation throws
     */
    public byte [] getPacket(final EOFInputStream input,
        final OutputStream output) throws IOException {

        // Packet format:
        //
        //   0   - SOH or STX
        //   1   - Seq
        //   2   - 255 - Seq
        //   3   - [ ... data ... ]
        //   N   - checksum or CRC

        int blockSize = 128;

        // Keep reading until we get a valid packet.
        for (;;) {
            boolean discard = false;

            try {

                if (DEBUG) {
                    System.err.println("Calling input.read()");
                }

                int blockType = input.read();

                if (DEBUG) {
                    System.err.printf("blockType: 0x%02x\n", blockType);
                }

                if (blockType == STX) {
                    blockSize = 1024;
                } else if (blockType == SOH) {
                    blockSize = 128;
                } else if (blockType == EOT) {
                    // Normal end of transmission.  ACK the EOT.
                    ack(output);
                    return new byte[0];
                } else if (blockType == CAN) {
                    // The remote side has cancelled the transfer.
                    addErrorMessage("TRANSFER CANCELLED BY SENDER");
                    abort(output);
                    return new byte[0];
                } else {
                    addErrorMessage("HEADER ERROR IN BLOCK #" + sequenceNumber);
                    purge(input, output);
                    continue;
                }

                // We got SOH/STX.  Now read the sequence number and its
                // complement.
                int seqByte = input.read();
                if (DEBUG) {
                    System.err.printf("seqByte: 0x%02x\n", seqByte);
                }
                if ((seqByte & 0xFF) == ((sequenceNumber - 1) & 0xFF)) {
                    addErrorMessage("DUPLICATE BLOCK #" + (sequenceNumber - 1));
                    if ((flavor == Flavor.X_1K_G) && (sequenceNumber == 2)) {
                        // The remote side is not honoring 1K/G mode.
                        // Downgrade to vanilla Xmodem 1K (switch NCGbyte to
                        // 'C').
                        addErrorMessage("DOWNGRADE TO XMODEM/1K");
                        flavor = Flavor.X_1K;
                    }
                    // Finish reading this block, and blindly ack it, but
                    // don't return it to the caller.
                    discard = true;
                } else if (seqByte != (sequenceNumber % 256)) {
                    addErrorMessage("BAD BLOCK NUMBER IN BLOCK #" + sequenceNumber);
                    purge(input, output);
                    continue;
                }

                int compSeqByte = input.read();

                if (discard == false) {
                    if ((255 - compSeqByte) != (sequenceNumber % 256)) {
                        addErrorMessage("COMPLIMENT BYTE BAD IN BLOCK #" +
                            sequenceNumber);
                        purge(input, output);
                        continue;
                    }

                    if (DEBUG) {
                        System.err.printf("SEQ: 0x%02x %d\n", sequenceNumber,
                            sequenceNumber);
                    }
                }

                // Now read the data.  Grab only up to blockSize.
                int blockReadN = 0;
                byte [] data = new byte[blockSize];
                while (blockReadN < data.length) {
                    int rc = input.read(data, blockReadN,
                        blockSize - blockReadN);
                    blockReadN += rc;
                }

                // Finally, check the checksum or CRC.
                if ((flavor == Flavor.VANILLA) || (flavor == Flavor.RELAXED)) {
                    // Checksum
                    int checksum = 0;
                    for (int i = 0; i < data.length; i++) {
                        int ch = ((int) data[i]) & 0xFF;
                        checksum += ch;
                    }
                    checksum = checksum & 0xFF;

                    int given = input.read();

                    if (discard == true) {
                        // This was a duplicate block, ACK it even if the
                        // data is crap.
                        if (flavor != Flavor.X_1K_G) {
                            // Send ACK
                            ack(output);
                        }
                        continue;
                    }

                    if (checksum != given) {
                        addErrorMessage("CHECKSUM ERROR IN BLOCK #" +
                            sequenceNumber);
                        purge(input, output);
                        continue;
                    }

                    // Good checksum, OK!
                    sequenceNumber++;
                    if (flavor != Flavor.X_1K_G) {
                        // Send ACK
                        ack(output);
                    }
                    consecutiveErrors = 0;
                    return data;
                }

                // CRC
                int crc = crc16(data);
                int given = input.read();
                int given2 = input.read();
                given = given << 8;
                given |= given2;

                if (discard == true) {
                    // This was a duplicate block, ACK it even if the data is
                    // crap.
                    if (flavor != Flavor.X_1K_G) {
                        // Send ACK
                        ack(output);
                    }
                    continue;
                }

                if (crc != given) {
                    addErrorMessage("CRC ERROR IN BLOCK #" +
                        sequenceNumber);
                    purge(input, output);
                    continue;
                }

                if (DEBUG) {
                    System.err.printf("Good CRC: 0x%04x\n", (given & 0xFFFF));
                }

                // Good CRC, OK!
                sequenceNumber++;
                if (flavor != Flavor.X_1K_G) {
                    ack(output);
                }
                consecutiveErrors = 0;
                return data;

            } catch (ReadTimeoutException e) {
                if (DEBUG) {
                    System.err.println("TIMEOUT");
                }
                addErrorMessage("TIMEOUT");
                if ((flavor == Flavor.X_1K_G) && (sequenceNumber == 2)) {
                    // The remote side is not honoring 1K/G mode.  Downgrade
                    // to vanilla Xmodem (switch NCGbyte to NAK).
                    addErrorMessage("DOWNGRADE TO XMODEM/1K");
                    flavor = Flavor.X_1K;
                }
                purge(input, output);
                continue;

            } catch (EOFException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                if (DEBUG) {
                    System.err.println("UNEXPECTED END OF TRANSMISSION");
                }
                addErrorMessage("UNEXPECTED END OF TRANSMISSION");
                abort(output);
                return new byte[0];
            }

        } // for (;;)

        // We should never get here.

    }

    /**
     * Get the timeout for this flavor of Xmodem.
     *
     * @return the number of millis for this flavor of Xmodem
     */
    public int getTimeout() {
        if (flavor == Flavor.RELAXED) {
            // Relaxed: 100 seconds
            return 100 * 1000;
        }
        // All others: 10 seconds
        return 10 * 1000;
    }

    /**
     * Send the appropriate "NAK/ACK" character for this flavor of Xmodem.
     *
     * @param output the stream to write to
     * @return true if successful
     */
    public boolean sendNCG(final OutputStream output) {
        try {
            switch (flavor) {
            case VANILLA:
            case RELAXED:
                // NAK
                output.write(NAK);
                break;
            case CRC:
            case X_1K:
                // 'C' - 0x43
                output.write('C');
                break;
            case X_1K_G:
                // 'G' - 0x47
                if (DEBUG) {
                    System.err.println("Requested -G");
                }
                output.write('G');
                break;
            }
            output.flush();
            return true;
        } catch (IOException e) {
            // We failed to get the byte out, all done.
            if (DEBUG) {
                e.printStackTrace();
            }
            addErrorMessage("UNABLE TO SEND STARTING NAK");
            abort(output);
            return false;
        }
    }

    /**
     * Compute the checksum or CRC and send that to the other side.
     *
     * @param output the stream to write to
     * @param data the block data
     * @throws IOException if a java.io operation throws
     */
    public void writeChecksum(final OutputStream output,
        byte [] data) throws IOException {

        if ((flavor == Flavor.VANILLA) || (flavor == Flavor.RELAXED)) {
            // Checksum
            int checksum = 0;
            for (int i = 0; i < data.length; i++) {
                int ch = ((int) data[i]) & 0xFF;
                checksum += ch;
            }
            output.write(checksum & 0xFF);
        } else {
            // CRC
            int crc = crc16(data);
            output.write((crc >> 8) & 0xFF);
            output.write( crc       & 0xFF);
        }
        output.flush();
    }

    /**
     * Read a 128 or 1024 byte block from file.
     *
     * @param file the file to read from
     * @return the bytes read, or null of the file is at EOF
     * @throws IOException if a java.io operation throws
     */
    public byte [] readFileBlock(final InputStream file) throws IOException {
        byte [] data = new byte[getBlockSize()];
        int rc = file.read(data);
        if (rc == data.length) {
            return data;
        }
        if (rc == -1) {
            // EOF
            return null;
        }
        // We have a shorter-than-asked block.  For file streams this is
        // typically the very last block.  But if we have a different kind of
        // stream it could just be an incomplete read.  Read either a full
        // block, or definitely hit EOF.

        int blockN = rc;
        while (blockN < data.length) {
            rc = file.read(data, blockN, data.length - blockN);
            if (rc == -1) {
                // Cool, EOF.  This will be the last block.
                if (blockN < 128) {
                    // We can use a shorter block, so do that.
                    byte [] shortBlock = new byte[128];
                    System.arraycopy(data, 0, shortBlock, 0, blockN);
                    data = shortBlock;
                }
                // Now pad it with CPM EOF.
                for (int i = blockN; i < data.length; i++) {
                    data[i] = 0x1A;
                }
                return data;
            }
            blockN += rc;
        }

        // We read several times, but now have a complete block.
        return data;
    }

    /**
     * Construct an instance to represent a file upload.
     *
     * @param flavor the Xmodem flavor to use
     * @param uploadFiles list of files to upload
     * @throws IllegalArgumentException if uploadFiles contains more than one
     * entry
     */
    public XmodemSession(final Flavor flavor,
        final LinkedList<FileInfo> uploadFiles) {

        super(uploadFiles);
        if (uploadFiles.size() != 1) {
            throw new IllegalArgumentException("Xmodem can only upload one " +
                "file at a time");
        }
        this.flavor = flavor;
        this.protocol = Protocol.XMODEM;
    }

    /**
     * Construct an instance to represent a single file upload or download.
     *
     * @param flavor the Xmodem flavor to use
     * @param file path to one file on the local filesystem
     * @param download If true, this session represents a download.  If
     * false, it represents an upload.
     */
    public XmodemSession(final Flavor flavor, final LocalFileInterface file,
        final boolean download) {

        super(file, download);
        this.flavor = flavor;
        this.protocol = Protocol.XMODEM;
    }

    /**
     * Cancel this entire file transfer.  The session state will become
     * ABORT.
     *
     * @param keepPartial If true, save whatever has been collected if this
     * was a download.  If false, delete the file.
     */
    public void cancelTransfer(boolean keepPartial) {
        // TODO: let upload()/download() know to both cancel and keep
        // partial.
    }

    /**
     * Skip this file and move to the next file in the transfer.  Note that
     * this does nothing for Xmodem.
     *
     * @param keepPartial If true, save whatever has been collected if this
     * was a download.  If false, delete the file.
     */
    public void skipFile(boolean keepPartial) {
        // Do nothing.  Xmodem cannot skip a file.
    }

}

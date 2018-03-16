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

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import jermit.io.EOFInputStream;
import jermit.io.LocalFile;
import jermit.io.LocalFileInterface;
import jermit.io.ReadTimeoutException;
import jermit.io.TimeoutInputStream;
import jermit.protocol.FileInfo;
import jermit.protocol.FileInfoModifier;
import jermit.protocol.Protocol;
import jermit.protocol.SerialFileTransferSession;

/**
 * ZmodemSession encapsulates all the state used by an upload or download
 * using the Zmodem protocol.
 */
public class ZmodemSession extends SerialFileTransferSession {

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    // If true, enable some debugging output.  Note package private access.
    static final boolean DEBUG = false;

    /**
     * The number of consecutive errors.  After 10 errors, the transfer is
     * cancelled.
     */
    private int consecutiveErrors = 0;

    /**
     * If 0, nothing was cancelled.  If 1, cancel and keep partial (default
     * when receiver cancels).  If 2, cancel and do not keep partial.
     */
    protected int cancelFlag = 0;

    /**
     * The bytes received from the remote side.
     */
    private EOFInputStream input;

    /**
     * The bytes sent to the remote side.
     */
    private OutputStream output;

    /**
     * If true, permit downloads to overwrite files.
     */
    private boolean overwrite = false;

    /**
     * The current state of the transfer.  Note package private access.
     */
    ZmodemState zmodemState = ZmodemState.INIT;

    /**
     * The size of the last block transferred.  Note package private access.
     */
    int lastBlockSize = 128;

    /**
     * Skip file mode.  0 = do nothing, 1 = skip this file and keep the
     * partial download, 2 = skip this file and delete it.  Note package
     * private access.
     */
    int skipFileMode = 0;

    /**
     * If true, use 32-bit CRC.  Note package private access.
     */
    boolean useCrc32 = false;

    /**
     * If true, escape control characters.  Note package private access.
     */
    boolean escapeControlChars = false;

    /**
     * If true, escape 8-bit characters.  Note package private access.
     */
    boolean escape8BitChars = false;

    /**
     * Header.encodeByte() is a simple lookup into this map.  Note package
     * private access.
     */
    byte [] encodeByteMap = new byte[256];

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Construct an instance to represent a file upload.
     *
     * @param input a stream that receives bytes sent by another Zmodem
     * instance
     * @param output a stream to sent bytes to another Zmodem instance
     * @param uploadFiles list of files to upload
     * @throws IllegalArgumentException if uploadFiles contains more than one
     * entry
     */
    public ZmodemSession(final InputStream input, final OutputStream output,
        final List<String> uploadFiles) {

        super(uploadFiles);
        this.protocol           = Protocol.ZMODEM;
        this.output             = output;
        this.currentFile        = -1;

        if (input instanceof TimeoutInputStream) {
            // Someone has already set the timeout.  Keep their value.
            this.input  = new EOFInputStream(input);
        } else {
            // Use the default value for this flavor of Xmodem.
            this.input  = new EOFInputStream(new TimeoutInputStream(input,
                    10 * 1000));
        }

        useCrc32 = System.getProperty(
                "jermit.zmodem.useCrc32","true").equals("true");
        escapeControlChars = System.getProperty(
                "jermit.zmodem.escapeControlChars", "false").equals("true");
        setupEncodeByteMap();
    }

    /**
     * Construct an instance to represent a batch download.
     *
     * @param input a stream that receives bytes sent by another Zmodem
     * instance
     * @param output a stream to sent bytes to another Zmodem instance
     * @param pathname the path to write received files to
     * @param overwrite if true, permit writing to files even if they already
     * exist
     */
    public ZmodemSession(final InputStream input, final OutputStream output,
        final String pathname, final boolean overwrite) {

        super(true);
        this.protocol           = Protocol.ZMODEM;
        this.output             = output;
        this.currentFile        = -1;

        if (input instanceof TimeoutInputStream) {
            // Someone has already set the timeout.  Keep their value.
            this.input  = new EOFInputStream(input);
        } else {
            // Use the default value for this flavor of Xmodem.
            this.input  = new EOFInputStream(new TimeoutInputStream(input,
                    10 * 1000));
        }
        this.overwrite          = overwrite;
        this.transferDirectory  = pathname;

        useCrc32 = System.getProperty(
                "jermit.zmodem.useCrc32","true").equals("true");
        escapeControlChars = System.getProperty(
                "jermit.zmodem.escapeControlChars", "false").equals("true");
        setupEncodeByteMap();
    }

    // ------------------------------------------------------------------------
    // SerialFileTransferSession ----------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get the block size.
     *
     * @return the block size
     */
    @Override
    public int getBlockSize() {
        return lastBlockSize;
    }

    /**
     * Get the batchable flag.
     *
     * @return If true, this protocol can transfer multiple files.  If false,
     * it can only transfer one file at a time.
     */
    @Override
    public boolean isBatchable() {
        return true;
    }

    /**
     * Set the state of this transfer.  Overridden to permit zmodem package
     * access.
     *
     * @param state one of the State enum values
     */
    @Override
    protected void setState(final State state) {
        super.setState(state);
    }

    /**
     * Add an INFO message to the messages list.  Overridden to permit zmodem
     * package access.
     *
     * @param message the message text
     */
    @Override
    protected synchronized void addInfoMessage(String message) {
        super.addInfoMessage(message);
    }

    /**
     * Add an ERROR message to the messages list.  Overridden to permit
     * zmodem package access.
     *
     * @param message the message text
     */
    @Override
    protected synchronized void addErrorMessage(String message) {
        super.addErrorMessage(message);
    }

    /**
     * Set the current file being transferred.  Overridden to permit zmodem
     * package access.
     *
     * @param currentFile the index in the files list
     */
    @Override
    protected synchronized void setCurrentFile(final int currentFile) {
        this.currentFile = currentFile;
    }

    /**
     * Cancel this entire file transfer.  The session state will become
     * ABORT.
     *
     * @param keepPartial If true, save whatever has been collected if this
     * was a download.  If false, delete the file.
     */
    @Override
    public void cancelTransfer(boolean keepPartial) {
        synchronized (this) {
            setState(State.ABORT);
            if (keepPartial == true) {
                cancelFlag = 1;
            } else {
                cancelFlag = 2;
            }
            addErrorMessage("CANCELLED BY USER");
        }
    }

    /**
     * Skip this file and move to the next file in the transfer.
     *
     * @param keepPartial If true, save whatever has been collected if this
     * was a download.  If false, delete the file.
     */
    @Override
    public void skipFile(boolean keepPartial) {
        if (keepPartial) {
            skipFileMode = 1;
        } else {
            skipFileMode = 2;
        }
    }

    /**
     * Get the protocol name.  Each protocol can have several variants.
     *
     * @return the protocol name for this transfer
     */
    @Override
    public String getProtocolName() {
        return "Zmodem";
    }

    // ------------------------------------------------------------------------
    // ZmodemSession ----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get input stream to the remote side.  Used by
     * ZmodemReceiver/ZmodemSender to cancel a pending read.
     *
     * @return the input stream
     */
    protected EOFInputStream getInput() {
        return input;
    }

    /**
     * Set the current status message.
     *
     * @param message the status message
     */
    protected synchronized void setCurrentStatus(final String message) {
        currentStatus = message;
    }

    /**
     * Set the number of bytes transferred in this session.  Note package
     * private access.
     *
     * @param bytesTransferred the number of bytes transferred in this
     * session
     */
    void setBytesTransferred(final long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }

    /**
     * Set the number of bytes in total to transfer in this session.  Note
     * package private access.
     *
     * @param bytesTotal the number of bytes in total to transfer in this
     * session
     */
    void setBytesTotal(final long bytesTotal) {
        this.bytesTotal = bytesTotal;
    }

    /**
     * Set the number of blocks transferred in this session.  Note package
     * private access.
     *
     * @param blocksTransferred the number of blocks transferred in this
     * session
     */
    void setBlocksTransferred(final long blocksTransferred) {
        this.blocksTransferred = blocksTransferred;
    }

    /**
     * Set the time at which last block was sent or received.  Note package
     * private access.
     *
     * @param lastBlockMillis the time at which last block was sent or
     * received
     */
    void setLastBlockMillis(final long lastBlockMillis) {
        this.lastBlockMillis = lastBlockMillis;
    }

    /**
     * Set the time at which this session started transferring its first
     * file.  Note package private access.
     *
     * @param startTime the time at which this session started transferring
     * its first file
     */
    void setStartTime(final long startTime) {
        this.startTime = startTime;
    }

    /**
     * Set the time at which this session completed transferring its last
     * file.  Note package private access.
     *
     * @param endTime the time at which this session completed transferring
     * its last file
     */
    void setEndTime(final long endTime) {
        this.endTime = endTime;
    }

    /**
     * Count a timeout, cancelling the transfer if there are too many
     * consecutive errors.  Note package private access.
     *
     * @throws IOException if a java.io operation throws
     */
    synchronized void timeout() throws IOException {
        if (DEBUG) {
            System.err.println("TIMEOUT");
        }
        addErrorMessage("TIMEOUT");
        consecutiveErrors++;
        if (consecutiveErrors == 10) {
            // Too many errors, we are done.
            abort("TOO MANY TIMEOUTS");
        }
    }

    /**
     * Abort the transfer.
     *
     * @param message text message to pass to addErrorMessage()
     * @throws IOException if a java.io operation throws
     */
    protected synchronized void abort(final String message) {
        if (DEBUG) {
            System.err.println("ABORT: " + message);
        }
        try {
            sendHeader(new ZAbort());
        } catch (IOException e) {
            if (DEBUG) {
                e.printStackTrace();
            }
        }
        synchronized (this) {
            setState(State.ABORT);
            cancelFlag = 1;
            addErrorMessage(message);
        }
    }

    /**
     * Create a FileInfoModifier for the current file being transferred.
     * This is used for ZmodemSender and ZmodemReceiver to get write access
     * to the FileInfo fields.
     */
    protected FileInfoModifier getCurrentFileInfoModifier() {
        FileInfo file = getCurrentFile();
        return getFileInfoModifier(file);
    }

    /**
     * Receive and decode a header from the wire.
     *
     * @param offset initial place in the ZDATA stream
     * @return header the header received
     * @throws IOException if a java.io operation throws
     * @throws ZmodemCancelledException if five Ctrl-X's are encountered in a
     * row
     */
    protected Header getHeader(final long offset) throws ReadTimeoutException,
        EOFException, IOException, ZmodemCancelledException {

        Header header = Header.decode(this, input, offset);
        if (header.parseState == Header.ParseState.OK) {
            consecutiveErrors = 0;
        } else {
            consecutiveErrors++;
            // TODO:
            //
            //   1. Change window up/down based on consecutive errors
            //   2. Emit error message
            //   3. Die on too many errors
        }
        return header;
    }

    /**
     * Encode and send a header onto the wire.
     *
     * @param header the header to send
     * @throws IOException if a java.io operation throws
     */
    protected void sendHeader(final Header header) throws IOException {
        byte [] headerBytes = header.encode(this);
        output.write(headerBytes);
        output.flush();

        if (DEBUG) {
            System.err.printf("Output %d bytes to wire: ",
                headerBytes.length);
            for (int i = 0; i < headerBytes.length; i++) {
                System.err.printf("%02x ", headerBytes[i]);
            }
            System.err.println();
        }
    }

    /**
     * Construct and send a ZNak packet onto the wire.
     *
     * @param seq sequence number of the packet
     * @throws IOException if a java.io operation throws
     */
    protected void sendZNak() throws IOException {
        Header header = new ZNak();
        byte [] headerBytes = header.encode(this);
        output.write(headerBytes);
        output.flush();

        if (DEBUG) {
            System.err.printf("Output %d bytes to wire: ", headerBytes.length);
            for (int i = 0; i < headerBytes.length; i++) {
                System.err.printf("%02x ", headerBytes[i]);
            }
            System.err.println();
        }
    }

    /**
     * Set up the encode byte map.  Note package private access.
     */
    void setupEncodeByteMap() {

        for (int ch = 0; ch < 256; ch++) {

            boolean encodeChar = false;

            /*
             * Oh boy, do we have another design flaw...  lrzsz does not
             * allow any regular characters to be encoded, so we cannot
             * protect against telnet, ssh, and rlogin sequences from
             * breaking the link.
             */
            switch (ch) {

            case Header.C_CAN:
            case Header.C_XON:
            case Header.C_XOFF:
            case (Header.C_XON | 0x80):
            case (Header.C_XOFF | 0x80):
                encodeChar = true;
                break;
            default:
                if ((ch < 0x20) && (escapeControlChars == true)) {
                    /*
                     * 7bit control char, encode only if requested
                     */
                    encodeChar = true;
                } else if ((ch >= 0x80) && (ch < 0xA0)) {
                    /*
                     * 8bit control char, always encode
                     */
                    encodeChar = true;
                } else if (((ch & 0x80) != 0)
                    && (escape8BitChars == true)
                ) {
                    /*
                     * 8bit char, encode only if requested
                     */
                    encodeChar = true;
                }
                break;
            }

            if (encodeChar == true) {
                /*
                 * Encode
                 */
                encodeByteMap[ch] = (byte) (ch | 0x40);
            } else if (ch == 0x7F) {
                /*
                 * Escaped control character: 0x7f
                 */
                encodeByteMap[ch] = 'l';
            } else if (ch == 0xFF) {
                /*
                 * Escaped control character: 0xff
                 */
                encodeByteMap[ch] = 'm';
            } else {
                /*
                 * Regular character
                 */
                encodeByteMap[ch] = (byte) ch;
            }
        }

        if (DEBUG) {
            System.err.println("Encode byte map:");
            for (int ch = 0; ch < 256; ch++) {
                System.err.printf(" [%02x = %02x]", ch, encodeByteMap[ch]);
                if ((ch % 6) == 5) {
                    System.err.println();
                }
            }
            System.err.println();
        }

    }

    /**
     * Open a file for download, checking for existence and overwriting if
     * necessary.
     *
     * @param filename the name of the file to open
     * @param fileModTime file modification time from the ZFile data
     * subpacket in millis, or -1 if unknown
     * @param fileSize file size from the ZFile data subpacket, or -1 if
     * unknown
     * @return true if the transfer is ready to download another file
     */
    protected boolean openDownloadFile(final String filename,
        final long fileModTime, final long fileSize) {

        // Make sure we cannot overwrite this file.
        assert (transferDirectory != null);
        assert (filename != null);
        File checkExists = new File(transferDirectory, filename);
        if (checkExists.exists() == false) {
            if (DEBUG) {
                System.err.printf("%s does not exist, OK\n", filename);
            }
            openDownloadFile(checkExists, fileModTime, fileSize);
            return true;
        }

        if (DEBUG) {
            System.err.printf("%s already exists, checking access...\n",
                filename);
        }

        if (overwrite) {
            // We are supposed to blow this file away
            if (DEBUG) {
                System.err.println("overwrite is true, BLOW IT AWAY!");
            }
            openDownloadFile(checkExists, fileModTime, fileSize);
            return true;
        }

        // TODO: crash recovery logic, ZSKIP and ZCRC
        // For now, always recover
        openDownloadFile(checkExists, fileModTime, fileSize);
        return true;
    }

    /**
     * Open a file for download.
     *
     * @param fileRef the file to open
     * @param fileModTime file modification time from the File-Attributes
     * packet in millis, or -1 if unknown
     * @param fileSize file size from the File-Attributes packet, or -1 if
     * unknown
     */
    private void openDownloadFile(final File fileRef,
        final long fileModTime, final long fileSize) {

        // TODO: allow callers to provide a class name for the
        // LocalFileInterface implementation and use reflection to get it.
        LocalFileInterface localFile = new LocalFile(fileRef);
        if (DEBUG) {
            System.err.println("Transfer directory: " + transferDirectory);
            System.err.println("Download to: " + localFile.getLocalName());
        }

        synchronized (this) {
            // Add the file to the files list and make it the current file.
            FileInfo file = new FileInfo(localFile);
            files.add(file);
            currentFile = files.size() - 1;

            // Now perform the stats update.  Since we have the file size we
            // can do it all though.

            // Set state BEFORE getCurrentFileModifier(), otherwise
            // getCurrentFile() might return null.
            setState(SerialFileTransferSession.State.TRANSFER);
            FileInfoModifier setFile = getCurrentFileInfoModifier();

            if (fileModTime >= 0) {
                setFile.setModTime(fileModTime);
            }
            setFile.setStartTime(System.currentTimeMillis());
            setFile.setBlockSize(getBlockSize());
            if (fileSize >= 0) {
                setFile.setBytesTotal(fileSize);
                setFile.setBlocksTotal(file.getBytesTotal() / getBlockSize());
                if (file.getBlocksTotal() * getBlockSize() <
                    file.getBytesTotal()
                ) {
                    setFile.setBlocksTotal(file.getBlocksTotal() + 1);
                }
                bytesTotal = bytesTotal + file.getBytesTotal();
            }
        }
    }

}

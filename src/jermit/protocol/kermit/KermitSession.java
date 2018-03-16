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
package jermit.protocol.kermit;

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
 * KermitSession encapsulates all the state used by an upload or download
 * using the Kermit protocol.
 */
public class KermitSession extends SerialFileTransferSession {

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    // If true, enable some debugging output.  Note package private access.
    static final boolean DEBUG = false;

    /**
     * The current sequence number.  Note package private access.
     */
    int sequenceNumber = 0;

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
    KermitState kermitState = KermitState.INIT;

    /**
     * The current transfer parameters.  Note package private access.
     */
    TransferParameters transferParameters = new TransferParameters();

    /**
     * The size of the last block transferred.  Note package private access.
     */
    int lastBlockSize = 128;

    /**
     * The raw bytes of the last packet sent out.
     */
    private byte [] lastPacketBytes;

    /**
     * Skip file mode.  0 = do nothing, 1 = skip this file and keep the
     * partial download, 2 = skip this file and delete it.  Note package
     * private access.
     */
    int skipFileMode = 0;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Construct an instance to represent a file upload.
     *
     * @param input a stream that receives bytes sent by another Kermit
     * instance
     * @param output a stream to sent bytes to another Kermit instance
     * @param uploadFiles list of files to upload
     * @throws IllegalArgumentException if uploadFiles contains more than one
     * entry
     */
    public KermitSession(final InputStream input, final OutputStream output,
        final List<String> uploadFiles) {

        super(uploadFiles);
        this.protocol           = Protocol.KERMIT;
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
    }

    /**
     * Construct an instance to represent a batch download.
     *
     * @param input a stream that receives bytes sent by another Kermit
     * instance
     * @param output a stream to sent bytes to another Kermit instance
     * @param pathname the path to write received files to
     * @param overwrite if true, permit writing to files even if they already
     * exist
     */
    public KermitSession(final InputStream input, final OutputStream output,
        final String pathname, final boolean overwrite) {

        super(true);
        this.protocol           = Protocol.KERMIT;
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
     * Set the state of this transfer.  Overridden to permit kermit package
     * access.
     *
     * @param state one of the State enum values
     */
    @Override
    protected void setState(final State state) {
        super.setState(state);
    }

    /**
     * Add an INFO message to the messages list.  Overridden to permit kermit
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
     * kermit package access.
     *
     * @param message the message text
     */
    @Override
    protected synchronized void addErrorMessage(String message) {
        super.addErrorMessage(message);
    }

    /**
     * Set the current file being transferred.  Overridden to permit kermit
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
        return "Kermit";
    }

    // ------------------------------------------------------------------------
    // KermitSession ----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Get input stream to the remote side.  Used by
     * KermitReceiver/KermitSender to cancel a pending read.
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
            sendError(message, sequenceNumber);
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
     * This is used for KermitSender and KermitReceiver to get write access
     * to the FileInfo fields.
     */
    protected FileInfoModifier getCurrentFileInfoModifier() {
        FileInfo file = getCurrentFile();
        return getFileInfoModifier(file);
    }

    /**
     * Receive and decode a packet from the wire.
     *
     * @return packet the packet received
     * @throws IOException if a java.io operation throws
     * @throws KermitCancelledException if three Ctrl-C's are encountered in
     * a row
     */
    protected Packet getPacket() throws ReadTimeoutException, EOFException,
                                        IOException, KermitCancelledException {

        Packet packet = Packet.decode(input, transferParameters, kermitState);
        if (packet.parseState == Packet.ParseState.OK) {
            consecutiveErrors = 0;
        } else {
            consecutiveErrors++;
            // TODO:
            //
            //   1. Emit error message
            //   2. Die on too many errors
        }
        return packet;
    }

    /**
     * Encode and send a packet onto the wire.
     *
     * @param packet the packet to send
     * @throws IOException if a java.io operation throws
     */
    protected void sendPacket(final Packet packet) throws IOException {
        lastPacketBytes = packet.encode(transferParameters);
        resendLastPacket();
    }

    /**
     * Resend the last packet onto the wire again.
     *
     * @throws IOException if a java.io operation throws
     */
    protected void resendLastPacket() throws IOException {
        output.write(lastPacketBytes);
        for (int i = 0; i < transferParameters.remote.NPAD; i++) {
            output.write(transferParameters.remote.PADC);
        }
        output.flush();

        if (DEBUG) {
            System.err.printf("Output %d bytes to wire: ",
                lastPacketBytes.length + transferParameters.remote.NPAD);
            for (int i = 0; i < lastPacketBytes.length; i++) {
                System.err.printf("%02x ", lastPacketBytes[i]);
            }
            for (int i = 0; i < transferParameters.remote.NPAD; i++) {
                System.err.printf("%02x ", transferParameters.remote.PADC);
            }
            System.err.println();
        }
    }

    /**
     * Construct and send a Nak packet onto the wire.
     *
     * @param seq sequence number of the packet
     * @throws IOException if a java.io operation throws
     */
    protected void sendNak(final int seq) throws IOException {
        Packet packet = new NakPacket(transferParameters.active.checkType, seq);
        byte [] packetBytes = packet.encode(transferParameters);
        output.write(packetBytes);
        for (int i = 0; i < transferParameters.remote.NPAD; i++) {
            output.write(transferParameters.remote.PADC);
        }
        output.flush();

        if (DEBUG) {
            System.err.printf("Output %d bytes to wire: ",
                packetBytes.length + transferParameters.remote.NPAD);
            for (int i = 0; i < packetBytes.length; i++) {
                System.err.printf("%02x ", packetBytes[i]);
            }
            for (int i = 0; i < transferParameters.remote.NPAD; i++) {
                System.err.printf("%02x ", transferParameters.remote.PADC);
            }
            System.err.println();
        }
    }

    /**
     * Construct and send an Ack packet onto the wire.
     *
     * @param seq sequence number of the packet
     * @throws IOException if a java.io operation throws
     */
    protected void sendAck(final byte seq) throws IOException {
        sendPacket(new AckPacket(transferParameters.active.checkType, seq));
    }

    /**
     * Construct and send an Ack packet onto the wire.
     *
     * @param seq sequence number of the packet
     * @throws IOException if a java.io operation throws
     */
    protected void sendAck(final int seq) throws IOException {
        sendPacket(new AckPacket(transferParameters.active.checkType, seq));
    }

    /**
     * Construct and send an Error packet onto the wire.
     *
     * @param message message to send to the remote side
     * @param seq sequence number of the packet
     * @throws IOException if a java.io operation throws
     */
    protected void sendError(final String message,
        final int seq) throws IOException {

        Packet packet = new ErrorPacket(message,
            transferParameters.active.checkType, seq);
        byte [] packetBytes = packet.encode(transferParameters);
        output.write(packetBytes);
        for (int i = 0; i < transferParameters.remote.NPAD; i++) {
            output.write(transferParameters.remote.PADC);
        }
        output.flush();

        if (DEBUG) {
            System.err.printf("Output %d bytes to wire: ",
                packetBytes.length + transferParameters.remote.NPAD);
            for (int i = 0; i < packetBytes.length; i++) {
                System.err.printf("%02x ", packetBytes[i]);
            }
            for (int i = 0; i < transferParameters.remote.NPAD; i++) {
                System.err.printf("%02x ", transferParameters.remote.PADC);
            }
            System.err.println();
        }
    }

    /**
     * Open a file for download, checking for existence and overwriting if
     * necessary.
     *
     * @param filename the name of the file to open
     * @param fileModTime file modification time from the File-Attributes
     * packet in millis, or -1 if unknown
     * @param fileSize file size from the File-Attributes packet, or -1 if
     * unknown
     * @param accessMode the requested access mode from the File-Attributes
     * packet, or null if unknown
     * @return true if the transfer is ready to download another file
     */
    protected boolean openDownloadFile(final String filename,
        final long fileModTime, final long fileSize,
        final FileAttributesPacket.NewFileAccessMode accessMode) {

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

        if (accessMode == null) {
            if (overwrite) {
                // We are supposed to blow this file away
                if (DEBUG) {
                    System.err.println("accessMode is null, overwrite is " +
                        "true, BLOW IT AWAY!");
                }
                openDownloadFile(checkExists, fileModTime, fileSize);
                return true;
            }

            if (DEBUG) {
                System.err.println("accessMode is null, overwrite is " +
                    "false, will not overwrite file");
            }
            abort(filename + " exists, will not overwrite");
            return false;
        }

        assert (accessMode != null);

        boolean needNewFile = false;
        switch (accessMode) {
        case NEW:
            if (DEBUG) {
                System.err.println("accessMode NEW, creating new filename\n");
            }
            needNewFile = true;
            break;
        case SUPERSEDE:
            if (DEBUG) {
                System.err.println("accessMode SUPERSEDE, refuse to honor.  " +
                    "Creating new filename instead.\n");
            }
            needNewFile = true;
            break;
        case WARN:
            // If we have resend support, then this could be crash recovery
            // instead.  The ONE thing Zmodem has over Kermit is the CRC
            // check it does when looking for file collisions, as one can use
            // that to get 95% correct behavior all the time: if the local
            // file already exists and is bigger than remote, then use a new
            // filename, else check CRC of overlapping regions and if
            // different use new filename, else recover.
            if (transferParameters.active.doResend) {
                if (DEBUG) {
                    System.err.println("accessMode WARN - try to resend");
                }
            } else {
                if (DEBUG) {
                    System.err.println("accessMode WARN - no crash recovery, " +
                        "so new filename instead.");
                }
                needNewFile = true;
            }
            break;
        case APPEND:
            // We are supposed to append on the end, even if we don't have a
            // file size.
            if (DEBUG) {
                System.err.println("accessMode APPEND, so append to same file");
            }
            break;
        }

        if (needNewFile) {
            // Find the next unused filename.
            for (int i = 0; i < 10000; i++) {
                checkExists = new File(transferDirectory,
                    String.format("%s.%04d", filename, i));
                if (!checkExists.exists()) {
                    break;
                }
            }
            if (checkExists.exists()) {
                // We tried 10000 new filenames, and they all exist.  Screw
                // this, use rsync.
                abort(filename + " exists, cannot find alternate name to use");
                return false;
            }
        }

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

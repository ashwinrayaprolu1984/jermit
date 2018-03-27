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
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import jermit.io.ReadTimeoutException;
import jermit.io.TimeoutInputStream;
import jermit.protocol.FileInfo;
import jermit.protocol.FileInfoModifier;
import jermit.protocol.SerialFileTransferSession;

/**
 * ZmodemSender uploads one or more files using the Zmodem protocol.
 */
public class ZmodemSender implements Runnable {

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    // If true, enable some debugging output.
    private static final boolean DEBUG = ZmodemSession.DEBUG;

    /**
     * The Zmodem session state.
     */
    private ZmodemSession session;

    /**
     * The stream to read file data from.
     */
    private InputStream fileInput = null;

    /**
     * The current position in the file.
     */
    private long filePosition = 0;

    /**
     * The current file being uploaded.
     */
    private FileInfo file = null;

    /**
     * The current file being uploaded properties setter.
     */
    private FileInfoModifier setFile = null;

    /**
     * Index in files of the file currently being transferred in this
     * session.
     */
    private int currentFile = -1;

    /**
     * The current block size.
     */
    private int blockSize = 1024;

    /**
     * If true, we are waiting for ZRPOS or ZACK to complete a file.
     */
    private boolean needEofAck = false;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Construct an instance to upload multiple files using existing I/O
     * Streams.
     *
     * @param input a stream that receives bytes sent by a Zmodem file sender
     * @param output a stream to sent bytes to a Zmodem file sender
     * @param uploadFiles list of files to upload
     */
    public ZmodemSender(final InputStream input, final OutputStream output,
        final List<String> uploadFiles) {

        session = new ZmodemSession(input, output, uploadFiles);
    }

    /**
     * Construct an instance to upload one file using existing I/O Streams.
     *
     * @param input a stream that receives bytes sent by a Zmodem file sender
     * @param output a stream to sent bytes to a Zmodem file sender
     * @param uploadFile the file name to upload
     */
    public ZmodemSender(final InputStream input, final OutputStream output,
        final String uploadFile) {

        List<String> uploadFiles = new ArrayList<String>();
        uploadFiles.add(uploadFile);

        session = new ZmodemSession(input, output, uploadFiles);
    }

    // ------------------------------------------------------------------------
    // Runnable ---------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Perform a file download using the Zmodem protocol.  Any exceptions
     * thrown will be emitted to System.err.
     */
    public void run() {

        // Start with init.
        session.setCurrentStatus("INIT");
        session.setStartTime(System.currentTimeMillis());

        boolean done = false;

        while ((session.cancelFlag == 0) && (done == false)) {

            try {

                if (DEBUG) {
                    System.err.println("run() zmodemState = " +
                        session.zmodemState);
                }

                switch (session.zmodemState) {

                case INIT:
                    session.zmodemState = ZmodemState.ZRQINIT;
                    break;

                case ZRQINIT:
                    sendBegin();
                    break;

                case ZRQINIT_WAIT:
                    sendBeginWait();
                    break;

                case ZSINIT:
                    sendInit();
                    break;

                case ZSINIT_WAIT:
                    sendInitWait();
                    break;

                case ZFILE:
                    sendFile();
                    break;

                case ZFILE_WAIT:
                    sendFileWait();
                    break;

                case ZEOF:
                    sendEof();
                    break;

                case ZEOF_WAIT:
                    sendEofWait();
                    break;

                case ZFIN:
                    sendFin();
                    break;

                case ZFIN_WAIT:
                    sendFinWait();
                    break;

                case COMPLETE:
                    done = true;
                    break;

                default:
                    throw new IllegalArgumentException("Internal error: " +
                        "unknown state " + session.zmodemState);

                } // switch (session.zmodemState)

            } catch (ReadTimeoutException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                try {
                    // We might get ReadTimeoutException as a result of the
                    // UI calling cancelTransfer() and that calling input's
                    // cancelRead() function.  So don't count that case as a
                    // real timeout.
                    if (session.cancelFlag == 0) {
                        session.timeout();
                    }
                } catch (IOException e2) {
                    if (DEBUG) {
                        e2.printStackTrace();
                    }
                    session.abort("NETWORK I/O ERROR DURING TIMEOUT");
                    session.cancelFlag = 1;
                }
            } catch (EOFException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                session.abort("UNEXPECTED END OF TRANSMISSION");
                session.cancelFlag = 1;
            } catch (IOException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                session.abort("NETWORK I/O ERROR");
                session.cancelFlag = 1;
            } catch (ZmodemCancelledException e) {
                if (DEBUG) {
                    e.printStackTrace();
                }
                session.abort("CANCELLED BY REMOTE SIDE");
                session.cancelFlag = 1;
            }

        } // while ((session.cancelFlag == 0) && (done == false))

        // Switch to the next file.
        synchronized (session) {
            if (done) {
                session.addInfoMessage("ALL FILES TRANSFERRED");

                // This is the success exit point for a batch.  Transfer was
                // not aborted or cancelled.
                session.setState(SerialFileTransferSession.State.END);
                session.setEndTime(System.currentTimeMillis());
            }
        }

    }

    // ------------------------------------------------------------------------
    // ZmodemSender -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Send a ZRQINIT to prompt the other side to send a ZRINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendBegin() throws IOException {
        if (DEBUG) {
            System.err.println("sendBegin() sending ZRQINIT...");
        }
        ZRQInitHeader zrqInit = new ZRQInitHeader();
        session.sendHeader(zrqInit);
        session.zmodemState = ZmodemState.ZRQINIT_WAIT;
        session.setCurrentStatus("SENDING ZRQINIT");
    }

    /**
     * Wait for a ZRINIT or ZCHALLENGE.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendBeginWait() throws ReadTimeoutException, EOFException,
                                           IOException,
                                           ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("sendBeginWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZRINIT");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  Resend the ZRQINIT.
            session.zmodemState = ZmodemState.ZRQINIT;
        } else if (header instanceof ZChallengeHeader) {
            // TODO: respond to ZCHALLENGE


        } else if (header instanceof ZRInitHeader) {
            // We got the remote side's ZRInit
            session.setCurrentStatus("ZRINIT");

            ZRInitHeader zrInit = (ZRInitHeader) header;

            // See what options were specified
            if ((zrInit.getFlags() & ZRInitHeader.TX_ESCAPE_CTRL) != 0) {
                session.escapeControlChars = true;
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_ESCAPE_CTRL");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_ESCAPE_8BIT) != 0) {
                session.escape8BitChars = true;
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_ESCAPE_8BIT");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_CAN_FULL_DUPLEX) != 0) {
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_CAN_FULL_DUPLEX");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_CAN_OVERLAP_IO) != 0) {
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_CAN_OVERLAP_IO");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_CAN_BREAK) != 0) {
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_CAN_BREAK");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_CAN_DECRYPT) != 0) {
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_CAN_DECRYPT");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_CAN_LZW) != 0) {
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_CAN_LZW");
                }
            }
            if ((zrInit.getFlags() & ZRInitHeader.TX_CAN_CRC32) != 0) {
                session.useCrc32 = true;
                if (DEBUG) {
                    System.err.println("sendBeginWait() TX_CAN_CRC32");
                }
            }

            // Update the encode map
            session.setupEncodeByteMap();

            // Move to the next state
            session.zmodemState = ZmodemState.ZSINIT;
        } else if (header instanceof ZAbortHeader) {
            // Remote side signalled error
            session.abort("ZABORT");
            session.cancelFlag = 1;
        } else {
            // Something else came in I'm not looking for.  This will always
            // be a protocol error.
            session.abort("PROTOCOL ERROR");
            session.cancelFlag = 1;
        }
    }

    /**
     * Send a ZSINIT to prompt the other side to send ZACK.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendInit() throws IOException {
        if (DEBUG) {
            System.err.println("sendInit() sending ZSINIT...");
        }
        ZSInitHeader zsInit = new ZSInitHeader(session);
        session.sendHeader(zsInit);
        session.zmodemState = ZmodemState.ZSINIT_WAIT;
        session.setCurrentStatus("SENDING ZSINIT");
    }

    /**
     * Wait for a ZACK.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendInitWait() throws ReadTimeoutException, EOFException,
                                       IOException, ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("sendInitWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZSINIT");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  Resend the ZSINIT.
            session.zmodemState = ZmodemState.ZSINIT;
        } else if (header instanceof ZAckHeader) {
            session.setCurrentStatus("ZACK");

            // Move to the next state
            session.zmodemState = ZmodemState.ZFILE;
        } else if (header instanceof ZRInitHeader) {
            // Receiver has repeated its ZRINIT because we were out of sync
            // with the ZRQINIT.  Ignore it.
        } else if (header instanceof ZAbortHeader) {
            // Remote side signalled error
            session.abort("ZABORT");
            session.cancelFlag = 1;
        } else {
            // Something else came in I'm not looking for.  This will always
            // be a protocol error.
            session.abort("PROTOCOL ERROR");
            session.cancelFlag = 1;
        }
    }

    /**
     * Send a ZSINIT to prompt the other side to send ZACK.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendFile() throws IOException {
        if (DEBUG) {
            System.err.println("sendFile() opening file...");
        }

        needEofAck = false;

        synchronized (session) {
            currentFile++;
            if (currentFile == session.getFiles().size()) {
                if (DEBUG) {
                    System.err.println("No more files");
                }
                // End of transfer.
                session.zmodemState = ZmodemState.ZFIN;
                return;
            }
            session.setCurrentFile(currentFile);
            session.setState(SerialFileTransferSession.State.TRANSFER);
            file = session.getCurrentFile();
            setFile = session.getCurrentFileInfoModifier();
            setFile.setStartTime(System.currentTimeMillis());
        }

        // Open the file.  Local try/catch to separate the read error
        // message from the generic network I/O error message.
        try {
            fileInput = file.getLocalFile().getInputStream();
        } catch (IOException e) {
            if (DEBUG) {
                e.printStackTrace();
            }
            session.abort("UNABLE TO READ FROM FILE " + file.getLocalName());
            session.cancelFlag = 1;
            return;
        }

        // Now that we have a transfer agreed to, and the file open, we can
        // update the file's total information for the UI.
        synchronized (session) {
            setFile.setBlockSize(session.getBlockSize());
            setFile.setBytesTotal(file.getLocalFile().getLength());
            setFile.setBlocksTotal(file.getBytesTotal() /
                session.getBlockSize());
            if (file.getBlocksTotal() * session.getBlockSize() <
                file.getBytesTotal()
            ) {
                setFile.setBlocksTotal(file.getBlocksTotal() + 1);
            }
            session.setBytesTotal(file.getBytesTotal());
        }

        // Put together the ZFILE header.
        String filename = file.getLocalName();
        if (DEBUG) {
            System.err.printf("Next file to upload: '%s' size %d\n",
                filename, file.getLocalFile().getLength());
        }
        String filePart = (new File(filename)).getName();
        ZFileHeader zFile = new ZFileHeader(filePart, file.getSize(),
            file.getModTime());
        filePosition = 0;
        session.sendHeader(zFile);
        session.setCurrentStatus("SENDING FILE");

        // Move to the next state
        session.zmodemState = ZmodemState.ZFILE_WAIT;
    }

    /**
     * Wait for a ZACK, ZRPOS, ZSKIP, or ZCRC.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendFileWait() throws ReadTimeoutException, EOFException,
                                       IOException, ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("sendFileWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZRPOS");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  Resend the ZFILE.
            session.zmodemState = ZmodemState.ZFILE;
        } else if (header instanceof ZRPosHeader) {
            session.setCurrentStatus("ZRPOS");

            ZRPosHeader zrPos = (ZRPosHeader) header;
            long remotePosition = zrPos.getPosition();
            if ((needEofAck == true)
                && (remotePosition == file.getLocalFile().getLength())
            ) {
                // We are at the final EOF.
                session.zmodemState = ZmodemState.ZEOF;
                return;
            }

            if (remotePosition != filePosition) {
                // Seek to the requested file position.
                if (fileInput instanceof FileInputStream) {
                    ((FileInputStream) fileInput).getChannel().
                        position(remotePosition);
                    filePosition = remotePosition;
                } else {
                    // TODO: handle different kinds of LocalFile.
                }
            }
            while (sendData() && (session.input.available() == 0)) {
                // Keep sending data until EOF or ack required.
            }
        } else if (header instanceof ZAckHeader) {
            ZAckHeader zAck = (ZAckHeader) header;
            long remotePosition = Header.bigToLittleEndian(zAck.getData());
            if ((needEofAck == true)
                && (remotePosition == file.getLocalFile().getLength())
            ) {
                // We are at the final EOF.
                session.zmodemState = ZmodemState.ZEOF;
                return;
            }
            // File data OK, continue on.
            while (sendData() && (session.input.available() == 0)) {
                // Keep sending data until EOF or ack required.
            }
        } else if (header instanceof ZAbortHeader) {
            // Remote side signalled error
            session.abort("ZABORT");
            session.cancelFlag = 1;
        } else {
            // Something else came in I'm not looking for.  This will always
            // be a protocol error.
            session.abort("PROTOCOL ERROR");
            session.cancelFlag = 1;
        }
    }

    /**
     * Send a ZEOF to prompt the other side to send ZRINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendEof() throws IOException {
        if (DEBUG) {
            System.err.println("sendInit() sending ZEOF...");
        }
        ZEofHeader zEof = new ZEofHeader((int) file.getLocalFile().getLength());
        session.sendHeader(zEof);
        session.zmodemState = ZmodemState.ZEOF_WAIT;
        session.setCurrentStatus("SENDING ZEOF");
    }

    /**
     * Wait for a ZRINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendEofWait() throws ReadTimeoutException, EOFException,
                                      IOException, ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("sendEofWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZRINIT");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  Resend the ZEOF.
            session.zmodemState = ZmodemState.ZEOF;
        } else if (header instanceof ZRInitHeader) {
            // We have completed this file.
            synchronized (session) {
                setFile.setBlocksTransferred(file.getBlocksTotal());
            }

            // Move to the next state
            session.zmodemState = ZmodemState.ZFILE;
        } else if (header instanceof ZAbortHeader) {
            // Remote side signalled error
            session.abort("ZABORT");
            session.cancelFlag = 1;
        } else {
            // Something else came in I'm not looking for.  This will always
            // be a protocol error.
            session.abort("PROTOCOL ERROR");
            session.cancelFlag = 1;
        }
    }

    /**
     * Send a ZFIN to prompt the other side to send ZFIN.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendFin() throws IOException {
        if (DEBUG) {
            System.err.println("sendInit() sending ZFIN...");
        }
        ZFinHeader zFin = new ZFinHeader();
        session.sendHeader(zFin);
        session.zmodemState = ZmodemState.ZFIN_WAIT;
        session.setCurrentStatus("SENDING ZFIN");
    }

    /**
     * Wait for a ZFIN.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendFinWait() throws ReadTimeoutException, EOFException,
                                      IOException, ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("sendFinWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZFIN");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  Resend the ZFIN.
            session.zmodemState = ZmodemState.ZFIN;
        } else if (header instanceof ZFinHeader) {
            // Write the "over-and-out" and call it done.
            session.output.write('O');
            session.output.write('O');
            session.output.flush();

            if (DEBUG) {
                System.err.println("ALL TRANSFERS COMPLETE");
            }
            session.zmodemState = ZmodemState.COMPLETE;
            session.setCurrentStatus("COMPLETE");
        } else if (header instanceof ZAbortHeader) {
            // Remote side signalled error
            session.abort("ZABORT");
            session.cancelFlag = 1;
        } else {
            // Something else came in I'm not looking for.  This will always
            // be a protocol error.
            session.abort("PROTOCOL ERROR");
            session.cancelFlag = 1;
        }
    }

    /**
     * Send a ZData header and subpacket.
     *
     * @return true if the subpacket should continue
     * @throws IOException if a java.io operation throws
     * @throws ZmodemCancelledException if three Ctrl-C's are encountered in
     * a row
     */
    private boolean sendData() throws IOException, ZmodemCancelledException {

        if (DEBUG) {
            System.err.printf("sendData() sending data subpacket at %d\n",
                filePosition);
        }

        // Default: stream along with a new header.
        session.crcType = Header.ZCRCE;

        // Read another subpacket's worth from the file and send it out.
        // session.sendHeader() will call header.encode() which performs the
        // actual reading.
        ZDataHeader dataHeader = new ZDataHeader(fileInput, filePosition,
            blockSize);
        if (dataHeader.eof) {
            session.crcType = Header.ZCRCW;
        }
        long sentBytes = dataHeader.getFileData().length;
        filePosition += sentBytes;
        session.sendHeader(dataHeader);
        session.setCurrentStatus("DATA");

        // Increment stats.
        synchronized (session) {
            setFile.setBytesTransferred(file.getBytesTransferred() +
                sentBytes);
            setFile.setBlocksTransferred(file.getBytesTransferred() /
                session.getBlockSize());
            session.setBytesTransferred(session.getBytesTransferred() +
                sentBytes);
            session.setBlocksTransferred(session.getBytesTransferred() /
                session.getBlockSize());
            session.setLastBlockMillis(System.currentTimeMillis());
        }

        // If this was our last header, switch to EOF.
        if (dataHeader.eof) {
            needEofAck = true;
            return false;
        }

        if ((session.crcType == Header.ZCRCQ)
            || (session.crcType == Header.ZCRCW)
        ) {
            return false;
        }
        return true;
    }

    /**
     * Get the session.
     *
     * @return the session for this transfer
     */
    public ZmodemSession getSession() {
        return session;
    }

    /**
     * Cancel this entire file transfer.  The session state will become
     * ABORT.
     *
     * @param keepPartial If true, save whatever has been collected if this
     * was a download.  If false, delete the file.
     */
    public void cancelTransfer(boolean keepPartial) {
        synchronized (session) {
            if (session.getCurrentFile() != null) {
                FileInfoModifier setFile = session.getCurrentFileInfoModifier();
                setFile.setEndTime(System.currentTimeMillis());
            }
            session.cancelTransfer(keepPartial);
            if (session.getInput().getStream() instanceof TimeoutInputStream) {
                ((TimeoutInputStream) session.getInput().
                    getStream()).cancelRead();
            }
        }
    }

    /**
     * Skip this file and move to the next file in the transfer.
     *
     * @param keepPartial If true, save whatever has been collected if this
     * was a download.  If false, delete the file.
     */
    public void skipFile(boolean keepPartial) {
        synchronized (session) {
            session.skipFile(keepPartial);
        }
    }

}

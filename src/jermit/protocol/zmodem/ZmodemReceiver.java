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
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import jermit.io.ReadTimeoutException;
import jermit.io.TimeoutInputStream;
import jermit.protocol.FileInfo;
import jermit.protocol.FileInfoModifier;
import jermit.protocol.SerialFileTransferSession;

/**
 * ZmodemReceiver downloads one or more files using the Zmodem protocol.
 */
public class ZmodemReceiver implements Runnable {

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
     * The name of the current file to download.  This will either come from
     * a File or a FileAttributes packet.
     */
    private String downloadFilename;

    /**
     * The stream to write file data to.
     */
    private OutputStream fileOutput = null;

    /**
     * The current file being downloaded.
     */
    private FileInfo file = null;

    /**
     * The current file being downloaded properties setter.
     */
    private FileInfoModifier setFile = null;

    /**
     * If set, delete the file right after saving it.  Used by skipFile()
     * when keepPartial is false.
     */
    private boolean deleteAfterEof = false;

    /**
     * The ZCHALLENGE value generated by receiveChallenge().  The other side
     * is expected to echo this back in a ZACK.
     */
    private int zChallengeValue = 0;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Construct an instance to download multiple files using existing I/O
     * Streams.
     *
     * @param input a stream that receives bytes sent by a Zmodem file sender
     * @param output a stream to sent bytes to a Zmodem file sender
     * @param pathname the path to write received files to
     * @param overwrite if true, permit writing to files even if they already
     * exist
     */
    public ZmodemReceiver(final InputStream input, final OutputStream output,
        final String pathname, final boolean overwrite) {

        session = new ZmodemSession(input, output, pathname, overwrite);
    }

    /**
     * Construct an instance to download multiple files using existing I/O
     * Streams.
     *
     * @param input a stream that receives bytes sent by a Zmodem file sender
     * @param output a stream to sent bytes to a Zmodem file sender
     * @param pathname the path to write received files to
     */
    public ZmodemReceiver(final InputStream input, final OutputStream output,
        final String pathname) {

        this(input, output, pathname, false);
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

                switch (session.zmodemState) {

                case INIT:
                    if (System.getProperty(
                        "jermit.zmodem.download.issueZChallenge",
                        "false").equals("true")
                    ) {
                        session.zmodemState = ZmodemState.ZCHALLENGE;
                    } else {
                        session.zmodemState = ZmodemState.ZRINIT;
                    }
                    break;

                case ZCHALLENGE:
                    receiveChallenge();
                    break;

                case ZCHALLENGE_WAIT:
                    receiveChallengeWait();
                    break;

                case ZRINIT:
                    receiveBegin();
                    break;

                case ZRINIT_WAIT:
                    receiveBeginWait();
                    break;

                case ZRPOS:
                    receivePosition();
                    break;

                case ZRPOS_WAIT:
                    receivePositionWait();
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
                // TODO
                /*
                try {
                    // We might get ReadTimeoutException as a result of the
                    // UI calling cancelTransfer() and that calling input's
                    // cancelRead() function.  So don't count that case as a
                    // real timeout.
                    if (session.cancelFlag == 0) {
                        session.timeout();
                    }
                    if (session.cancelFlag == 0) {
                        // timeout() didn't kill the session, keep going.
                        session.sendNak(session.sequenceNumber);
                    }
                } catch (IOException e2) {
                    if (DEBUG) {
                        e2.printStackTrace();
                    }
                    session.abort("NETWORK I/O ERROR DURING TIMEOUT");
                    session.cancelFlag = 1;
                }
                */
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
    // ZmodemReceiver ---------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Send a ZCHALLENGE to prompt the other side to send a ZACK.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receiveChallenge() throws IOException {
        if (DEBUG) {
            System.err.println("receiveChallenge() sending ZCHALLENGE...");
        }
        ZChallengeHeader zChallenge = new ZChallengeHeader();
        zChallengeValue = zChallenge.getChallengeValue();
        session.sendHeader(zChallenge);
        session.zmodemState = ZmodemState.ZCHALLENGE_WAIT;
        session.setCurrentStatus("SENDING ZCHALLENGE");
    }

    /**
     * Wait for a ZACK to ZCHALLENGE, and in response either kill the
     * transfer or send ZRINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receiveChallengeWait() throws ReadTimeoutException,
                                               EOFException, IOException,
                                               ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("receiveChallengeWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZACK TO ZCHALLENGE");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  NAK it.
            session.sendZNak();
        } else if (header instanceof ZRQInitHeader) {
            // Sender sent its first ZRQINIT, ignore it and wait for its
            // actual ZCHALLENGE response.
        } else if (header instanceof ZAckHeader) {
            // We got the remote side's ZAck
            session.setCurrentStatus("ZACK");

            ZAckHeader ack = (ZAckHeader) header;
            if (ack.getData() == zChallengeValue) {
                // All OK, move to the next state
                session.setCurrentStatus("ZCHALLENGE -- OK");
                session.zmodemState = ZmodemState.ZRINIT;
            } else {
                // Remote side failed to do the ZCHALLENGE correctly
                session.abort("ZCHALLENGE FAILED");
                session.cancelFlag = 1;
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
     * Send a ZRINIT to prompt the other side to send a ZRQINIT, ZSINIT, or
     * ZFILE.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receiveBegin() throws IOException {
        if (DEBUG) {
            System.err.println("receiveBegin() sending ZRINIT...");
        }
        session.setupEncodeByteMap();
        ZRInitHeader zrInit = new ZRInitHeader(session);
        session.sendHeader(zrInit);
        session.zmodemState = ZmodemState.ZRINIT_WAIT;
        session.setCurrentStatus("SENDING ZRINIT");
    }

    /**
     * Wait for a ZRQINIT, and in response send either ZCHALLENGE or ZRINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receiveBeginWait() throws ReadTimeoutException, EOFException,
                                           IOException,
                                           ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("receiveBeginWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZRQINIT");

        Header header = session.getHeader();
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  NAK it.
            session.sendZNak();
        } else if (header instanceof ZRQInitHeader) {
            // Sender has repeated its ZRQINIT, re-send the ZRINIT response.
            ZRInitHeader zrInit = new ZRInitHeader(session);
            session.sendHeader(zrInit);
            session.zmodemState = ZmodemState.ZRINIT_WAIT;
            session.setCurrentStatus("SENDING ZRINIT");
        } else if (header instanceof ZSInitHeader) {
            // We got the remote side's ZSInit
            session.setCurrentStatus("ZSINIT");

            ZSInitHeader zsInit = (ZSInitHeader) header;
            if ((zsInit.getFlags() & ZRInitHeader.TX_ESCAPE_CTRL) != 0) {
                session.escapeControlChars = true;
                if (DEBUG) {
                    System.err.println("receiveBeginWait() TX_ESCAPE_CTRL");
                }
            }
            if ((zsInit.getFlags() & ZRInitHeader.TX_ESCAPE_8BIT) != 0) {
                session.escape8BitChars = true;
                if (DEBUG) {
                    System.err.println("receiveBeginWait() TX_ESCAPE_8BIT");
                }
            }

            // Update the encode map
            session.setupEncodeByteMap();

            // ZACK the ZSINIT
            session.sendHeader(new ZAckHeader());

            // Now wait on the ZFILE header, do nothing here.
        } else if (header instanceof ZFileHeader) {
            ZFileHeader fileHeader = (ZFileHeader) header;
            if (session.openDownloadFile(fileHeader.filename,
                    fileHeader.fileModTime, fileHeader.fileSize)
            ) {
                synchronized (session) {
                    file = session.getCurrentFile();
                    setFile = session.getCurrentFileInfoModifier();
                    fileOutput = file.getLocalFile().getOutputStream();
                }
            } else {
                return;
            }

            // Move to the next state
            session.zmodemState = ZmodemState.ZRPOS;
        } else if (header instanceof ZFinHeader) {
            // Send the completion ZFIN
            session.sendHeader(new ZFinHeader());

            // Transfer has ended
            synchronized (session) {
                session.addInfoMessage("SUCCESS");
                session.setState(SerialFileTransferSession.State.FILE_DONE);
            }
            session.zmodemState = ZmodemState.COMPLETE;
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
     * Send a ZRPOS to prompt the other side to send ZDATA or ZEOF.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receivePosition() throws IOException {
        if (DEBUG) {
            System.err.println("receivePosition() sending ZRPOS...");
        }
        ZRPosHeader zrPos = new ZRPosHeader((int) file.getBytesTransferred());
        session.sendHeader(zrPos);
        session.zmodemState = ZmodemState.ZRPOS_WAIT;
        session.setCurrentStatus("SENDING ZRPOS");
    }

    /**
     * Wait for a ZDATA with file data.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receivePositionWait() throws ReadTimeoutException,
                                              EOFException, IOException,
                                              ZmodemCancelledException {

        if (DEBUG) {
            System.err.println("receivePositionWait() waiting for response...");
        }
        session.setCurrentStatus("WAITING FOR ZDATA");

        Header header = session.getHeader(file.getBytesTransferred());
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  Resend the ZRPOS.
            ZRPosHeader zrPos = new ZRPosHeader((int) file.getBytesTransferred());
            session.sendHeader(zrPos);
        } else if (header instanceof ZFileHeader) {
            // Bug in 'sz -e': it can send the ZFILE twice.  Ignore this one.
        } else if (header instanceof ZDataHeader) {
            // Got data, save it.
            ZDataHeader zData = (ZDataHeader) header;
            receiveSaveData((ZDataHeader) header);
        } else if (header instanceof ZEofHeader) {
            ZEofHeader zEof = (ZEofHeader) header;
            if (zEof.getFileSize() == file.getBytesTransferred()) {
                // Save the file and setup for the next file.
                synchronized (session) {
                    setFile.setEndTime(System.currentTimeMillis());
                }

                try {
                    fileOutput.close();
                } catch (IOException e) {
                    // SQUASH
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                }

                if (file.getModTime() > 0) {
                    if (DEBUG) {
                        System.err.println("Setting file mod time to " +
                            (new java.util.Date(file.getModTime())));
                    }

                    try {
                        file.getLocalFile().setTime(file.getModTime());
                    } catch (IOException e) {
                        if (DEBUG) {
                            System.err.println("Warning: error updating file time");
                            e.printStackTrace();
                        }
                    }
                }

                if (deleteAfterEof) {
                    // The user asked to discard the partial, so do that.
                    file.getLocalFile().delete();
                    deleteAfterEof = false;
                }

                // Reset for a new file.
                file = null;
                setFile = null;
                fileOutput = null;

                // All OK, move to the next state
                session.setCurrentStatus("ZEOF");
                session.zmodemState = ZmodemState.ZRINIT;
            } else {
                // We are still out of sync, resend ZRPOS.
                //
                // TODO:
                //
                //   Actually there are two cases.  If EOF < bytes, then the
                //   file got smaller somehow in transit.  If EOF > bytes,
                //   then the file is growing on disk.  Either of these is
                //   not ideal, but what should we do here?
                ZRPosHeader zrPos = new ZRPosHeader((int) file.getBytesTransferred());
                session.sendHeader(zrPos);
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
     * Save a ZDataHeader to file.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receiveSaveData(final ZDataHeader zData) throws IOException {
        if (DEBUG) {
            System.err.print("receiveSaveData() saving to file...");
        }

        try {
            byte [] fileData = zData.getFileData();
            fileOutput.write(fileData);

            if (DEBUG) {
                System.err.printf(" wrote %d bytes to file\n", fileData.length);
            }

            // Update stats
            synchronized (session) {
                session.lastBlockSize = fileData.length;
                setFile.setBlockSize(fileData.length);
                setFile.setBytesTransferred(file.getBytesTransferred() +
                    fileData.length);
                session.setBytesTransferred(session.getBytesTransferred() +
                    fileData.length);
                setFile.setBlocksTransferred(file.getBytesTransferred() /
                    session.getBlockSize());
                session.setBlocksTransferred(session.getBytesTransferred() /
                    session.getBlockSize());
                session.setLastBlockMillis(System.currentTimeMillis());
                setFile.setBlocksTotal(file.getBytesTotal() /
                    file.getBlockSize());
            }

        } catch (IOException e) {
            if (DEBUG) {
                e.printStackTrace();
            }
            session.abort("UNABLE TO WRITE TO FILE " + file.getLocalName());
            session.cancelFlag = 1;
            return;
        }
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

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
                    session.zmodemState = ZmodemState.ZRINIT;
                    break;

                case ZRINIT:
                    receiveBegin();
                    break;

                case ZRINIT_WAIT:
                    receiveBeginWait();
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
     * Send a ZRINIT to prompt the other side to send a ZSINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void receiveBegin() throws IOException {
        if (DEBUG) {
            System.err.println("receiveBegin() sending ZRINIT...");
        }
        ZRInit zrInit = new ZRInit();
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
        } else if (header instanceof ZSInit) {
            // We got the remote side's ZSInit
            session.setCurrentStatus("ZSINIT");

            // TODO: look at ZSInit



            // Move to the next state
            session.zmodemState = ZmodemState.ZFILE;
        } else if (header instanceof ZAbort) {
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

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

    /**
     * Send a ZRQINIT to prompt the other side to send a ZRINIT.
     *
     * @throws IOException if a java.io operation throws
     */
    private void sendBegin() throws IOException {
        if (DEBUG) {
            System.err.println("sendBegin() sending ZRQINIT...");
        }
        ZRQInit zrqInit = new ZRQInit();
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

        Header header = session.getHeader(0);
        if (header.parseState != Header.ParseState.OK) {
            // We had an error.  NAK it.
            session.sendZNak();
        } else if (header instanceof ZRInit) {
            // We got the remote side's ZRInit
            session.setCurrentStatus("ZRINIT");

            // TODO: look at ZRInit



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

}

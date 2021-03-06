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
package jermit.tests;

import jermit.tests.kermit.*;
import jermit.tests.xmodem.*;
import jermit.tests.ymodem.*;
import jermit.tests.zmodem.*;

/**
 * Execute all of the transfer tests.
 */
public class TestAll {

    /**
     * Main entry point.
     *
     * @param args Command line arguments
     */
    public static void main(final String [] args) {
        try {
            System.out.println("--- Executing all Jermit tests... ---");

            if (true) {

            /*
             * XMODEM ---------------------------------------------------------
             */

            /*
             * Downloads - clean line.
             */
            (new Xmodem1()).doTest();
            (new Xmodem2()).doTest();
            (new Xmodem3()).doTest();
            (new Xmodem4()).doTest();
            (new Xmodem5()).doTest();
            (new Xmodem6()).doTest();

            /*
             * Uploads - clean line.
             */
            (new Xmodem7()).doTest();
            (new Xmodem9()).doTest();
            (new Xmodem10()).doTest();
            /*
            // This one uses throttled input streams, don't run it normally.
            (new Xmodem8()).doTest();
             */

            /*
             * Noisy transfers.
             */
            (new Xmodem11()).doTest();
            (new Xmodem12()).doTest();

            /*
             * YMODEM ---------------------------------------------------------
             */
            (new Ymodem1()).doTest();
            (new Ymodem2()).doTest();
            (new Ymodem3()).doTest();
            (new Ymodem4()).doTest();
            (new Ymodem5()).doTest();

            }

            /*
             * KERMIT ---------------------------------------------------------
             */

            if (true) {

            /*
             * Basic uploads and downloads - clean line.
             */
            System.setProperty("jermit.kermit.streaming", "false");
            (new Kermit1()).doTest();
            (new Kermit2()).doTest();
            (new Kermit3()).doTest();
            (new Kermit4()).doTest();
            (new Kermit5()).doTest();

            /*
             * Streaming tests.  Turn it off afterwards.
             */
            System.setProperty("jermit.kermit.streaming", "true");
            (new Kermit6()).doTest();
            (new Kermit7()).doTest();
            System.setProperty("jermit.kermit.streaming", "false");

            /*
             * Noisy tests.
             */
            (new Kermit8()).doTest();
            (new Kermit9()).doTest();

            }

            /*
             * ZMODEM ---------------------------------------------------------
             */

            if (true) {

            /*
             * Basic uploads and downloads - clean line.
             */
            (new Zmodem1()).doTest();
            (new Zmodem2()).doTest();
            (new Zmodem3()).doTest();
            (new Zmodem4()).doTest();
            (new Zmodem5()).doTest();

            }

            if (false) {

            /*
             * Escaped control character tests.  Turn it off afterwards.
             */
            System.setProperty("jermit.zmodem.escapeControlChars", "true");
            (new Zmodem6()).doTest();
            (new Zmodem7()).doTest();
            System.setProperty("jermit.zmodem.escapeControlChars", "false");

            /*
             * Noisy tests.
             */
            (new Zmodem8()).doTest();
            (new Zmodem9()).doTest();

            }

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

}

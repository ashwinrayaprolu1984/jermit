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
package jermit.tests.zmodem;

import java.io.File;
import java.io.IOException;

import jermit.protocol.zmodem.ZmodemReceiver;
import jermit.tests.SerialTransferTest;
import jermit.tests.TestFailedException;

/**
 * Test a basic Zmodem file transfer.
 */
public class Zmodem3 extends SerialTransferTest implements Runnable {

    class FilePair {
        public String name;
        public String tmpSourceName;
        public String tmpSourcePath;
        public String tmpDestName;
        public String tmpDestPath;
    }

    /**
     * Public constructor.
     */
    public Zmodem3() {
    }

    /**
     * Run the test.
     */
    @Override
    public void doTest() throws IOException, TestFailedException {
        System.out.printf("Zmodem3: 4 binary file downloads\n");

        // Process:
        //
        //   1. Extract jermit/tests/data/lady-of-shalott.jpg to
        //      a temp file.
        //   2. Extract jermit/tests/data/qm5.zip to
        //      a temp file.
        //   3. Extract jermit/tests/data/William-Adolphe_Bouguereau_(1825-1905)_-_A_Young_Girl_Defending_Herself_Against_Eros_(1880).jpg
        //      to a temp file.
        //   4. Extract jermit/tests/data/rfc856.txt to a temp file.
        //   5. Spawn 'zmodem -i -s /path/to/temp1 /path/to/temp2 ...'
        //   6. Spin up ZmodemReceiver to download to a temp directory.
        //   7. Read all file pairs and compare contents.

        FilePair [] pairs = new FilePair[4];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = new FilePair();
        }
        pairs[0].name = "lady-of-shalott.jpg";
        pairs[1].name = "qm5.zip";
        pairs[2].name = "William-Adolphe_Bouguereau_(1825-1905)_-_A_Young_Girl_Defending_Herself_Against_Eros_(1880).jpg";
        pairs[3].name = "rfc856.txt";

        for (int i = 0; i < pairs.length; i++) {
            File source = File.createTempFile("send-zmodem", "");
            saveResourceToFile("jermit/tests/data/" + pairs[i].name, source);
            source.deleteOnExit();
            pairs[i].tmpSourceName = source.getName();
            pairs[i].tmpSourcePath = source.getPath();
        }

        // Create a directory
        File destinationDirName = File.createTempFile("receive-zmodem", "");
        String destinationPath = destinationDirName.getPath();
        destinationDirName.delete();
        File destinationDir = new File(destinationPath);
        destinationDir.mkdir();
        destinationDir.deleteOnExit();

        for (int i = 0; i < pairs.length; i++) {
            File destination = new File(destinationPath,
                pairs[i].tmpSourceName);
            destination.deleteOnExit();
            pairs[i].tmpDestName = destination.getName();
            pairs[i].tmpDestPath = destination.getPath();
        }

        ProcessBuilder zmodemPB = new ProcessBuilder("sz", "-w", "1024",
            pairs[0].tmpSourcePath,
            pairs[1].tmpSourcePath,
            pairs[2].tmpSourcePath,
            pairs[3].tmpSourcePath);
        Process zmodemSender = zmodemPB.start();

        // Allow overwrite of destination file, because we just created it.
        ZmodemReceiver zmodemReceiver = new ZmodemReceiver(
                zmodemSender.getInputStream(), zmodemSender.getOutputStream(),
                destinationPath, false);

        zmodemReceiver.run();
        for (int i = 0; i < pairs.length; i++) {
            if (!compareFiles(pairs[i].tmpSourcePath, pairs[i].tmpDestPath)) {
                throw new TestFailedException(pairs[i].name +
                    ": Files are not the same");
            }
        }
    }

    /**
     * Run the test.  Any exceptions thrown will be emitted to System.err.
     */
    public void run() {
        try {
            doTest();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Main entry point.
     *
     * @param args Command line arguments
     */
    public static void main(final String [] args) {
        try {
            Zmodem3 test = new Zmodem3();
            test.doTest();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

}

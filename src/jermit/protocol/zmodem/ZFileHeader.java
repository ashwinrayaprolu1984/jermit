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

import java.io.File;
import java.io.UnsupportedEncodingException;

/**
 * ZFileHeader is used to pass a filename and metadata.
 */
class ZFileHeader extends Header {

    // ------------------------------------------------------------------------
    // Variables --------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Name of file.
     */
    public String filename = null;

    /**
     * Size of file in bytes.
     */
    public long fileSize = -1;

    /**
     * Modification time of file.
     */
    public long fileModTime = -1;

    // ------------------------------------------------------------------------
    // Constructors -----------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Public constructor.
     */
    public ZFileHeader() {
        this(0);
    }

    /**
     * Public constructor.
     *
     * @param data the data field for this header
     */
    public ZFileHeader(final int data) {
        super(Type.ZFILE, (byte) 0x04, "ZFILE", data);
    }

    /**
     * Public constructor.
     *
     * @param filename name of file
     * @param fileSize size of file
     * @param fileModTime modification time of file
     */
    public ZFileHeader(final String filename, final long fileSize,
        final long fileModTime) {

        this(0);
        this.filename    = filename;
        this.fileSize    = fileSize;
        this.fileModTime = fileModTime;
    }

    // ------------------------------------------------------------------------
    // Header -----------------------------------------------------------------
    // ------------------------------------------------------------------------

    /**
     * Parse whatever came in a data subpacket.  Used by subclasses to put
     * data into fields.
     *
     * @param subpacket the bytes of the subpacket
     */
    @Override
    protected void parseDataSubpacket(final byte [] subpacket) {
        // Pull the fields from the subpacket: file name, size, and
        // timestamp.  The rest is defined in the zmodem spec but "optional"
        // so we cannot rely on it.
        int begin = 0;
        for (int i = begin; i < subpacket.length; i++) {
            if (subpacket[i] == ' ') {
                if ((i - begin) > 1) {
                    try {
                        // We have a value, is it filename or file size?
                        if (filename == null) {
                            // This is part of the filename, ignore it.
                        } else if (fileSize == -1) {
                            // This is the file size.
                            String fileSizeString = new String(subpacket, begin,
                                i - begin, "UTF-8");
                            fileSize = Long.parseLong(fileSizeString);
                            begin = i + 1;
                        } else if (fileModTime == -1) {
                            // This is the file time.
                            String fileModTimeString = new String(subpacket,
                                begin, i - begin, "UTF-8");
                            fileModTime = Long.parseLong(fileModTimeString, 8);

                            // We are done looking for values.
                            break;
                        }
                    } catch (UnsupportedEncodingException e) {
                        parseState = ParseState.PROTO_DATA_INVALID;
                        return;
                    }
                }
            }

            if (subpacket[i] == 0) {
                if ((i - begin) > 1) {
                    try {
                        // We have a value, is it filename or file size?
                        if (filename == null) {
                            // Filename it is.
                            filename = new String(subpacket, begin, i - begin,
                                "UTF-8");
                        } else if (fileSize == -1) {
                            // File size.
                            String fileSizeString = new String(subpacket, begin,
                                i - begin, "UTF-8");
                            System.err.println("i " + i + " begin " + begin);
                            System.err.println("fileSizeString '" +
                                fileSizeString + "'");

                            fileSize = Long.parseLong(fileSizeString);

                            // We are done looking for values.
                            break;
                        }
                    } catch (UnsupportedEncodingException e) {
                        parseState = ParseState.PROTO_DATA_INVALID;
                        return;
                    }
                }
                // Skip past the NUL and set the beginning of the next
                // string.
                i++;
                begin = i;
            }
        }

        if (DEBUG) {
            System.err.println("Name: '" + filename + "'");
            System.err.println("Size: " + fileSize + " bytes");
            System.err.println("Time: " + fileModTime + " seconds");
        }
    }

    /**
     * Get the data subpacket raw bytes.  Used by subclasses to serialize
     * fields into data.
     *
     * @return the bytes of the subpacket
     */
    @Override
    protected byte [] createDataSubpacket() {
        try {
            String filePart = (new File(filename)).getName();
            byte [] name = filePart.getBytes("UTF-8");
            byte [] size = Long.toString(fileSize).getBytes("UTF-8");
            byte [] modtime = Long.toOctalString(
            fileModTime / 1000).getBytes("UTF-8");
            byte [] data = new byte[name.length + size.length +
                modtime.length + 2];
            System.arraycopy(name, 0, data, 0, name.length);
            System.arraycopy(size, 0, data, name.length + 1, size.length);
            data[name.length + size.length + 1] = ' ';
            System.arraycopy(modtime, 0, data,
                name.length + 1 + size.length + 1, modtime.length);
            return data;
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

}

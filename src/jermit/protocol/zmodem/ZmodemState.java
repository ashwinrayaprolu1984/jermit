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

/**
 * Available states for the Zmodem transfer.
 */
enum ZmodemState {

    /**
     * Before the first byte is sent.
     */
    INIT,

    /**
     * Transfer(s) complete.
     */
    COMPLETE,

    /**
     * Transfer was aborted due to excessive timeouts, user abort, or
     * other error.
     */
    ABORT,

    /**
     * Receiver: send ZRINIT.
     */
    ZRINIT,

    /**
     * Receiver: waiting for ZFILE or ZSINIT.
     */
    ZRINIT_WAIT,

    /**
     * Receiver: send ZCHALLENGE.
     */
    ZCHALLENGE,

    /**
     * Receiver: waiting for ZACK.
     */
    ZCHALLENGE_WAIT,

    /**
     * Receiver: send ZRPOS.
     */
    ZRPOS,

    /**
     * Receiver: waiting for ZDATA.
     */
    ZRPOS_WAIT,

    /**
     * Receiver: send ZSKIP.
     */
    ZSKIP,

    /**
     * Receiver: send ZCRC.
     */
    ZCRC,

    /**
     * Receiver: waiting for ZCRC.
     */
    ZCRC_WAIT,

    /**
     * Sender: send ZRQINIT.
     */
    ZRQINIT,

    /**
     * Sender: waiting for ZRINIT or ZCHALLENGE.
     */
    ZRQINIT_WAIT,

    /**
     * Sender: send ZSINIT.
     */
    ZSINIT,

    /**
     * Sender: waiting for ZACK.
     */
    ZSINIT_WAIT,

    /**
     * Sender: send ZFILE.
     */
    ZFILE,

    /**
     * Sender: waiting for ZSKIP, ZCRC, or ZRPOS.
     */
    ZFILE_WAIT,

    /**
     * Sender: send ZEOF.
     */
    ZEOF,

    /**
     * Sender: waiting for ZRINIT.
     */
    ZEOF_WAIT,

    /**
     * Sender: send ZFIN.
     */
    ZFIN,

    /**
     * Sender: waiting for ZFIN.
     */
    ZFIN_WAIT

}

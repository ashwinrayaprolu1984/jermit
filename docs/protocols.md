Jermit Protocols
================

Xmodem
------

Jermit supports the following variants of Xmodem:

* Vanilla.  This is the "standard" Xmodem: 128 byte blocks, 10 second
  timeout, and a simple checksum.

* Relaxed.  This is vanilla Xmodem with a 100 second timeout.  This
  was originally developed sometime before Qmodem(tm) release 2.2
  (August 24, 1986) for users who had trouble with Xmodem transfers on
  CompuServe.

* CRC.  This variant uses 128 byte blocks, 10 second timeout, and a
  16-bit CRC function.

* 1K.  This variant has 1024 and 128 byte blocks, 10 second timeout,
  and a 16-bit CRC function.

* 1K/G.  This is the same as 1K but it does not wait on
  acknowledgements between blocks, just streaming the blocks.  Any
  error will terminate the transfer.

Xmodem has the unfortunate capability of corrupting files that
terminate in the ASCII CAN byte (0x18).  The final block of an Xmodem
transfer pads the file with CANs, and without the true file length
being known the receiver will either strip these CANs (and thus may
strip one or more that are supposed to be there) or leave them (and
thus make the file bigger than it should be).

In the days of BBSes, one would use Xmodem only to download a program
capable of running Ymodem or Zmodem.

The general philosophy of Xmodem could be summed up as: "get these
disk sectors over there".  It was the first to arrive, it worked on
noisy connections, and it got the contents of files copied OK.  But it
lacked any consideration of speed, file metadata, or out-of-band
signalling.

Ymodem
------

Jermit supports the following variants of Ymodem:

* Vanilla.  This is the "standard" Ymodem: 1024 byte blocks, 10 second
  timeout, a 16-bit CRC function, with file size and file modification
  time.

* Ymodem/G.  This is the same as vanilla but it does not wait on
  acknowledgements between blocks, just streaming the blocks.  Any
  error will terminate the transfer.

Ymodem/G is the fastest of the old serial protocols, having the least
overhead (4 bytes overhead per 1024 bytes data).  However, it is the
most fragile: it requires an 8-bit clean channel with error
correction.  (Also, the Omen Tech rzsz 'rb' Ymodem receiver cannot
specify streaming behavior, so Ymodem/G is only good when using 'sb'
on a remote machine to download to one's local machine.)

Ymodem's philosophy statement might be: "get multiple files
transferred, with correct file time and size".  Like Xmodem, it lacked
consideration for speed, additional file metadata (e.g. owner,
permissions), or out-of-band signalling.

Kermit
------

Jermit supports the following features of Kermit:

* Normal and extended-length (up to 9k) packets.

* Streaming (requires reliable channel, e.g. TCP).

* File Attributes, including correct time stamp and unmangled
  filename.

* RESEND / file resume support.

* (TODO) Full duplex sliding windows.

* (TODO) Internet Kermit Server, including SSL support.

* (TODO) Non-standard bidirectional file transfer support.

Kermit is the best all-round serial file transfer protocol available.
It is fast, versatile, and has excellent fundamentals.  If one must
pick a serial protocol, they would do well to first prove that Kermit
is the wrong choice before looking further.

Kermit's primary obstacle to modern adoption has been its long and
storied history.  Kermit was the first protocol to accomplish many
kinds of file transfers, and it was developed before the network
effect had winnowed down the dozens of unique platforms into the big
three remaining today (mainframe, POSIX, Windows).  Its standards
document thus lists IDs for extinct systems, odd things to do with a
file (e.g. mail it, print it, or even execute it!), and the codepage
translation system developed afterwards was made irrelevant by
standardization on UTF-8.

But if one implements just the basics of encoding/decoding for eight
packet types, they have a reliable data transfer protocol that works
across serial ports, UDP packets, and TCP streams.  (One can also
escape any character sequence as needed, e.g. the "~." sequence used
to terminate ssh links.  Protecting ssh like this is impossible in
Zmodem.)

Kermit's philosophy is: "reliably transfer text and binary files
successfully across all known systems, with fallback capability for
earlier versions".  Kermit's packet format was a huge advance, as was
session negotiation.  But its content-awareness was a two-edged sword:
it was exceedingly useful to have things like EBCDIC-to-ASCII and for
"free", but it forced a lot of system awareness into the protocol.

Zmodem
------

Jermit supports the following features of Zmodem:

* (TODO) Block size up to 8k.

* (TODO) Crash recovery / file resume.

* (TODO) Automatic packet size changes in response to line conditions.

Zmodem was the most widely used serial file transfer protocol in the
BBS / dialup era.  It could not work in nearly as many conditions as
Kermit, but when it did work it was very fast indeed.  Zmodem also
pioneered the "autostart" and had a better file recovery mechanism.
Combined with the relative ubiquity of 8-bit-clean channels in the BBS
era, more friendly sysop tools, and a pretty awful smear campaign
waged against Kermit, Zmodem ended up winning the BBS world over.

Zmodem's philisophy is most definitely: "get multiple files
transferred, with correct file time and size, and do it quickly".
Zmodem uses a mix of packets like Kermit and its own stripped-down
"data subpackets" (really just a TCP-like stream of bytes with a few
CRCs interspersed).  It strives to get the session negotiation over
with as quickly as possible and then get to the streaming part.

Bridgit
-------

Jermit will feature a new protocol (currently codenamed Bridgit) that
will be a synthesis of dialup protocol learnings plus several newer
ideas from the Internet era.  See docs/bridgit/ for more information.

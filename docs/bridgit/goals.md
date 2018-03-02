Bridgit Protocol Design Goals
=============================

Bridgit is a new protocol that combines dialup protocol experience
with newer learnings from the Internet era.  This document outlines
the design goals and features intended to get there.

Goals
-----

* Be extensible for the future, i.e. have a negotiation format that
  can expand with new uses.

* Only pay for those features that are used.  Be capable of graceful
  fallback for features unsupported by both sides of the link.

* Be able to transfer files between disparate systems.  Today these
  are: Windows, POSIX, mainframe, embedded, and industrial controls.

* For the barebones transfer, be straightforward to implement in all
  popular programming languages.  (C, Java, Javascript, Python, etc.)

* Be able to run over any physical or logical layer, including
  unreliable transports.  This includes direct serial, raw ethernet
  frames, IP, UDP, and TCP.

* Be able to run on 7-bit and 8-bit connections.

* Be able to run over unidirectional links, i.e. have a "data diode"
  mode: senders transmit bytes without expecting replies, and
  receivers can reconstruct files using a capture of the senders
  packets.  (This is intended for two uses: 1. Testing senders and
  receivers using a standardized test suite of captures.  2.
  Integrating into secure systems in which a trusted zone broadcasts
  data out to untrusted networks, such as industrial control systems
  communicating data out to a reporting system on the business
  network.)

* Pass raw data unaltered between these systems, i.e. make no
  decisions regarding binary, text, records, and so on.
  Post-transfer conversions are performed by other programs.

* Support encryption (including chaff) to protect against
  eavesdroppers, and compression to improve throughput.

* Support bidirectional syncronization of files and directories,
  including the notion of set theory (inner, outer, right, and left
  joins).

* Be able to support a "no file transfer" mode, i.e. provide a
  reliable and optionally encrypted/compressed channel for other uses.

* Support out-of-band data such as user chat messages during transfer.

* For the reference implementation: Have an API permitting pluggable
  integration into existing systems, including hooks for pre- and
  post-file and batch transfer actions.  (This enables integration
  into format converters (pre/post), authorization checks (pre), and
  malware scanners (post).)

Features
--------

* Reliability:

  - Transfer across both 7-bit and 8-bit channels cleanly.  Use
    Kermit's low-level encoding/decoding wire protocol, including RLE.

  - Transfer across both stream-oriented (TCP, reliable serial) and
    datagram-oriented (UDP, unreliable serial) channels.  When
    stream-oriented, act like Kermit streaming; when packet-oriented,
    act like Kermit sliding windows.

  - Be capable of escaping any byte or byte sequence.  Protect
    transport layer from file contents being interpreted as commands.

  - Support XON/XOFF, PADC, NPAD, TIME.

* Performance:

  - On reliable links, add no extra data to file contents.  (Like
    Zmodem data subpacket.)

  - Compress file contents before encoding.

  - Gracefully downgrade to FTP-like performance when features are not
    used.  (Be like C++: only pay for the features you use.)

* Data synchronization:

  - Synchronize files, file contents, and file metadata (including
    unknown future attributes), and do so bidirectionally.  (Behave
    like Unison when bidirectional, rsync for unidirectional.)

  - Detect and recover from interruptions.  (Use a Zmodem-like CRC or
    true hash to ensure the overlapping part is the same.)

  - Overwrite files by part rather than whole.  (Use the rsync
    algorithm to minimize what needs to be transferred.)

* Security:

  - Encrypt the link.  (Support pluggable crypto algorithms, like
    ssh/TLS.  Design for PFS from the get-go for negotiated keys.  But
    no PKI or protection from MITM: for that use real ssh/TLS.)

  - Provide bidirectional chaff.  (Needs a good PRNG, and work to
    ensure it doesn't create a known plaintext attack.  Could chaff
    also be used for deniable encryption support?)

  - Be able to run in a "data diode" mode, i.e. the sending side
    transmits and assumes all is successful.  (Obviously loses the
    ability to re-sync on line errors.  Could I add a parity block
    and/or Grey code to permit reconstruction for some data loss?
    Have to see how those hold up to the classic insert/delete bytes
    behavior of serial ports.  This mode would in effect allow one to
    capture a file transfer and replay it later...  Ooooohhh that would
    be useful.  In data diode mode encryption is still OK, but must
    provide the symmetric key manually, no public-key exchange.)

* Automation:

  - Support multiple sessions back-to-back.  (Like IKS.)

  - Both sender and receiver can init a transfer, with a standard init
    string.  (Like Zmodem and Kermit.)

* User Interface:

  - Out-of-band messages to display to the user.  (Support messages
    and chats.)

  - Optional full batch statistics on both sides of bidirectional
    transfer.  (Like HS/Link.)

  - Permit skipping per-file and per-batch, from both ends.  (Like
    Kermit.)

  - Permit session without file transfer at all.  (Like ssh/IKS.)

* Application Support:

  - Permit skipping inside a file, from both ends.  (Be capable of
    being a streaming format.)

  - Hooks in code for plugins to process files as they come in and
    after they complete.  (Show images/media during download, open
    zips after completion.)

  - Have an application-defined packet type for generic bidirectional
    streaming.  (Be able to provide encryption, compression, and
    reliability across serial links to any kind of application.)

Obviously this will be far more complex than any of the previous
protocols.  But it would provide a foundation for building resilient
and secure store-and-forward networks, that could run over nearly any
kind of link without requiring the complexities of PPP or TCP/IP.

The philosophy of this new protocol is: "Be the Swiss army knife of
file transfer."

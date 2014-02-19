archistar-bft [![Build Status](https://travis-ci.org/Archistar/archistar-bft.png?branch=master)](https://travis-ci.org/Archistar/archistar-bft)
=============

archistar-bft is an Java Open-Source library. It was developed for and within the [Archistar-core](https://github.com/Archistar/archistar-core) project, in 2014 the BFT algorithm's state engine was extracted into a library of its own. The reasoning behind extracting the library was to create a platform- and transport-independent BFT engine which can be reused within other projects. This allows for a very clear and compact code base (currently ~800LOC BFT code and 200LOC test cases).

For example [Archistar](https://github.com/Archistar/archistar-core) combines the BFT state-machine with a [netty.io http transport](http://netty.io). We hope that other projects can reuse this code and/or extend it.

As the code handles an abstract BFT state-machine test-cases can easily be written without the need of a network simulation software. The test-cases themself should be meaningful enough to get an better overview of BFT.

Current ongoing research is mostly in the fault-tolerance area. The view-change code is lacking at best, state-transfer call-backs need to be implemented. Help is always welcome.

Byzantine Fault Tolerance (BFT) Algorithms
------------------------------------------

Byzantine Fault Tolerance's objective is to find consensus within a distributed system in which every participating client or server can fail. In addition to "normal" stop-faults (faulty components stop communicating or signal an error) byzantine-fault-tolerant systems can deal with arbitrary errors, i.e. components can maliciously produce faulty outputs or corrupt their internal state.

Initially this subject was breached by [Lamport, Shostak and Pease in 1982](http://research.microsoft.com/en-us/um/people/lamport/pubs/byz.pdf), but the proposed protocols were too expensive for general use. In 1999 Castro and Liskov's [Practical Byzantine Fault Tolerance](http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.127.6130) kindled new research into BFT algorithms. Protocols like [Zyzzyva](http://dl.acm.org/citation.cfm?id=1294267) further improved good-case performance, but [Clement, Wong, Alvisi, Dahlin](https://www.cs.utexas.edu/~aclement/aardvark-tr.pdf) found those protocols susceptible to denial-of-service attacks and proposed Aardvark, a robust PBFT implementation with constant performance under pressure. Archistar uses an Aardvark-influenced BFT protocol with adoptions for secret-sharing algorithm integration.

BFT algorithms impose restrictions upon the minimal server count. Algorithms without special hardware requirements typically need at lease 3f+1 to 5f+1 servers for handling f faulty servers. In addition servers (also called replicas in BFT terminology) contain active logic. They are able to perform cryptographic algorithms, manage multiple collections of operation states as well as communicate with each other.


Differences to other solutions
------------------------------

In PBFT a client initiates an operation by sending the operation to each of the 3f+1 replicas. For our storage use-case this would mandate that an encrypted copy of the whole user dataset would be sent to each server -- thus beating privacy. Within the Archistar BFT protocol the client splits up data into 3f+1 encrypted fragments, each server only receives one of the fragments. The used secret-sharing algorithm ensures that a single replica is not able to reconstruct the plaintext.

One important non-functional requirement of Archistar is a ``hackable'' code-base. As we embarked on testing different algorithms and protocols lean and agile source code was paramount. We believe in Abstract's and Aardvark's commitment to software engineering.

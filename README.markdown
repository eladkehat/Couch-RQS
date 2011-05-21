Relaxed Queue Service
=====================

Couch-RQS (Relaxed Queue Service) is an open source queue system that enables
applications to submit and receive messages through a web interface.
It is based on CouchDB, a robust, fast and easy to use document-oriented
database - http://couchdb.apache.org/

The queue interface is provided by Java code (CouchDB itself is written in
Erlang).


Inspiration
-----------

The inspiration for Couch-RQS was [Amazon SQS][0] (Simple Queue Service).

Like SQS, RQS lets components of your application communicate through a queue
buffer that is accessible through HTTP. However, RQS trades-off SQS' high
availability guarantee for real queue functionality (see the wiki pages for
a detailed comparison).
Also, RQS is complete free and runs on your own infrastructure - completely
under your control.


Key Features
------------

* The queue is accessible as a web service. Different components in your
application can access it from multiple locations - just run a CouchDB instance
on a machine that is accessible to the rest of your applications.
* Guaranteed message delivery: dequeued messages remain in the queue, hidden,
until deleted by the acquiring process. If that process crushes before it
finished processing the message, the message will appear again after a preset
timeout period, to be consumed by another process.
* Supports FIFO/LIFO functionality. Messages are guaranteed to be consumed in
sequence.
* Very large message payload size (up to 4GB, limited by available RAM on the
server - as per CouchDB limitations).
* Messages are guaranteed to arrive only once, and are guaranteed to arrive if
they're available. No need to code around these SQS limitations.


More Goodness
-------------

* Relies on the very robust and generally wonderful CouchDB.
* It is simple to implement a hot backup (or multiple backups) of your queue
data across the network using CouchDB's replication mechanism.
* Simple Java API.
* Unlike SQS, it's open source and completely free.


Check out the wiki pages to learn more

[0]: http://aws.amazon.com/sqs/
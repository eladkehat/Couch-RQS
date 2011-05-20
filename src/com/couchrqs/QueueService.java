/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

import com.jzboy.couchdb.CouchDBException;
import com.jzboy.couchdb.Database;
import com.jzboy.couchdb.Server;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * This class provides methods for manipulating queues.
 * 
 * @see com.couchrqs.Queue
 */
public class QueueService {

	static final String DESIGN_DOC_JSON = "{\"views\":{" +
		"\"" + Queue.RQS_PENDING_VIEW_NAME + "\":{\"map\":\"function(doc) { if(!doc.lock) emit(doc.sent_at, null);}\"}," +
		"\"" + Queue.RQS_LOCKED_VIEW_NAME + "\":{\"map\":\"function(doc) { if(doc.lock) emit(doc.lock.locked_at, null);}\"}}}";

	final Server couchDB;

	/**
	 * Creates a new queue service that works with the CouchDB instance at the specified location.
	 * @param host		where to find your CouchDB instance
	 * @param port		port of your CouchDB instance
	 */
	public QueueService(String host, int port) {
		couchDB = new Server(host, port);
	}

	/**
	 * Creates a new queue service that works with a local CouchDB instance listening on the default port.
	 */
	public QueueService() {
		couchDB = new Server();
	}

	/**
	 * Get a Queue object given that queue's name.
	 *
	 * @throws NoSuchQueueException	if a queue with the specified name does not exist on the server
	 * @throws RQSException		wraps any exception thrown by the underlying CouchDB layer
	 */
	public Queue getQueue(String queueName) throws NoSuchQueueException, RQSException {
		Queue queue = new Queue(couchDB, queueName);
		if (!isQueue(queueName))
			throw new NoSuchQueueException("Queue not found: " + queueName);
		return queue;
	}

	/**
	 * Creates a new queue with the specified name.
	 *
	 * @throws QueueNameAlreadyTakenException	if there already exists a database with this name on the server
	 * @throws RQSException					wraps any exception thrown by the underlying CouchDB layer
	 */
	public Queue createQueue(String queueName) throws QueueNameAlreadyTakenException, RQSException {
		if (!isNameAvailable(queueName))
			throw new QueueNameAlreadyTakenException("Database already exists: " + queueName);
		Queue queue = new Queue(couchDB, queueName);
		initQueue(queue);
		return queue;
	}

	/**
	 * Either get a queue - like {@link #getQueue(java.lang.String) }, or create a new one - like
	 * {@link #createQueue(java.lang.String) } if it doesn't exist yet.
	 *
	 * @throws QueueNameAlreadyTakenException	if there already exists a database with this name on the server,
	 * that is <strong>not</strong> a RQS queue
	 * @throws RQSException					wraps any exception thrown by the underlying CouchDB layer
	 */
	public Queue getOrCreateQueue(String queueName) throws QueueNameAlreadyTakenException, RQSException {
		if (isNameAvailable(queueName))
			return createQueue(queueName);
		if (isQueue(queueName))
			return new Queue(couchDB, queueName);
		// there is such a database, and it isn't a queue
		throw new QueueNameAlreadyTakenException("Non-RQS Database already exists: " + queueName);
	}

	/**
	 * Initializes a Couch database for use by RQS by creating a design document with the necessary views.
	 */
	private void initQueue(Queue queue) throws RQSException {
		try {
			queue.db.create();
			queue.db.createDocument("_design/"+Queue.RQS_DESIGN_DOC_NAME, DESIGN_DOC_JSON, false);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Check if <code>queueName</code> can be used to create a new queue.
	 * @return true iff there exists no database on the CoudhDB server with this name
	 */
	private boolean isNameAvailable(String queueName) throws RQSException {
		try {
			Database db = new Database(this.couchDB, queueName);
			return !db.exists();
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Check if <code>queueName</code> identifies a RQS <code>Queue</code> on this server.
	 * @return true iff there is a database with that name, <strong>and</strong> that database
	 * is a queue, as determined by the existence of a RQS design document in that database.
	 *
	 * @throws RQSException	wraps any exception thrown by the underlying CouchDB layer
	 */
	public boolean isQueue(String queueName) throws RQSException {
		if (isNameAvailable(queueName))
			return false;
		Database db = new Database(this.couchDB, queueName);
		try {
			// the following method throws an exception if the design doc isn't found
			db.designDocumentInfo(Queue.RQS_DESIGN_DOC_NAME);
			return true;
		} catch (CouchDBException cdbe) {
			if (cdbe.getStatusCode() == 404)
				return false;
			else
				throw new RQSException(cdbe);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Returns a list of all queue names on this server.
	 * Effectively, this means all databases that have a RQS design document.
	 * <p>
	 * Note that this method is inefficient when there is a large number of databases on the server,
	 * as it takes the list of all databases and checks each one separately.
	 *
	 * @see #isQueue(java.lang.String)
	 *
	 * @throws RQSException	wraps any exception thrown by the underlying CouchDB layer
	 */
	public List<String> listQueues() throws RQSException {
		ArrayList<String> dbNames;
		try {
			dbNames = couchDB.allDbs();
		} catch (Exception e) {
			throw new RQSException(e);
		}
		ListIterator<String> lit = dbNames.listIterator();
		while (lit.hasNext()) {
			String queueName = lit.next();
			if (!isQueue(queueName))
				lit.remove();
		}
		return dbNames;
	}

	/**
	 * Deletes the specified queue from the server.
	 * This operation is irreversible.
	 * <p>
	 * Only works on RQS queues - won't delete a CouchDB database with that name which isn't a queue
	 * @return true iff a queue named <code>queueName</code> was found and deleted
	 * 
	 * @throws RQSException	wraps any exception thrown by the underlying CouchDB layer
	 */
	public boolean deleteQueue(String queueName) throws RQSException {
		try {
			if (!isQueue(queueName))
				return false;
			new Database(this.couchDB, queueName).delete();
			return true;
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	@Override
	public String toString() {
		return "QueueService on " + this.couchDB.toString();
	}

}

/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

import com.jzboy.couchdb.CouchDBException;
import com.jzboy.couchdb.Database;
import com.jzboy.couchdb.Document;
import com.jzboy.couchdb.Server;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;

/**
 * This class provides methods that enqueue, dequeue and delete queue messages.
 * Queues are acquired through the methods in QueueService.
 * <p>
 * You can use <code>Queue</code> as a stack and receive messages in LIFO order. Note however that
 * FIFO/LIFO style isn't enforced anywhere, so it is up to your code to do this in a consistent manner.
 * <p>
 * The queue orders messages using timestamps assigned locally using the system time.
 * If processes on different machines add messages to the queue, discrepancies in system clocks
 * affect the ordering of messages.
 */
public class Queue {

	static final String RQS_DESIGN_DOC_NAME = "couchrqs";
	static final String	RQS_PENDING_VIEW_NAME = "pending";
	static final String RQS_LOCKED_VIEW_NAME = "locked";

	static final long DEFAULT_VISIBILITY_TIMEOUT = 30000;
	static final String MESSAGE_ATTACHMENT_NAME = "message";
	static final String MESSAGE_MIME_TYPE = "application/octet-stream";

	final Database db;
	/** The visibility timeout (in milliseconds) to use for messages in this queue. */
	private long visibilityTimeout;
	/** Identification used in locking messages. */
	private String processId;

	public Queue(Server couchDB, String name) {
		db = new Database(couchDB, name);
		visibilityTimeout = DEFAULT_VISIBILITY_TIMEOUT;
		processId = ManagementFactory.getRuntimeMXBean().getName();
	}

	public String getName() {
		return db.getDbName();
	}

	/**
	 * Get the String by which this process identifies itself on the CouchDB server when locking messages.<br />
	 * Unless set with {@link #setProcessId(java.lang.String) } it defaults to a name retrieved from the JVM
	 * via <code>ManagementFactory.getRuntimeMXBean().getName()</code>
	 */
	public String getProcessId() {
		return processId;
	}

	/**
	 * Get the String by which this process identifies itself on the CouchDB server when locking messages.
	 */
	public void setProcessId(String processId) {
		this.processId = processId;
	}

	/**
	 * Return this queue's default message visibility timeout, in milliseconds.
	 */
	public long getVisibilityTimeout() {
		return visibilityTimeout;
	}

	/**
	 * Set this queue's default message visibility timeout, in milliseconds.<br />
	 * This timeout is used when receiving messages, unless a different timeout is specified.
	 */
	public void setVisibilityTimeout(long visibilityTimeout) {
		this.visibilityTimeout = visibilityTimeout;
	}

	/**
	 * Add a message to the queue.<br />
	 * Treats the message as an opaque binary. No parsing is performed on it and it is added as
	 * a document attachment to CouchDB.
	 * <p>
	 * There is no explicit limit on the size of the data, altough effectively CouchDB can handle messages
	 * up to 4 GB and a further limit may be imposed by the amount of RAM on your server.
	 *
	 * @param data	the message content
	 * @return	an id that uniquely identifies the message on this queue
	 * @throws RQSException	wraps any exception thrown by the underlying CouchDB layer
	 */
	public String sendMessage(byte[] data) throws RQSException {
		try {
			String uuid = db.getServer().nextUUID();
			ObjectNode json = new ObjectNode(JsonNodeFactory.instance);
			json.put("sent_at", System.currentTimeMillis());
			Document doc = db.createDocument(new Document(uuid, json));
			db.saveAttachment(doc, MESSAGE_ATTACHMENT_NAME, data, MESSAGE_MIME_TYPE);
			return uuid;
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Get as many as maxNumberOfMessages messages from the specified view.<br />
	 * The descending param is used to get LIFO (if true) or FIFO (if false) behavior.
	 */
	private List<Document> getPendingDocsFromView(String viewName, 
												  final int maxNumberOfMessages,
												  final boolean descending)
			throws RQSException
	{
		List<NameValuePair> params = new ArrayList<NameValuePair>() {{
				add(new BasicNameValuePair("include_docs", "true"));
				add(new BasicNameValuePair("limit", String.valueOf(maxNumberOfMessages)));
				if (descending)
					add(new BasicNameValuePair("descending", "true"));
		}};
		try {
			return db.queryView(RQS_DESIGN_DOC_NAME, viewName, params);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Get the specified messages from the database.
	 */
	private List<Document> getDocsFromDatabase(List<String> messageIds) throws RQSException	{
		try {
			return db.getDocuments(messageIds, true);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Create a JSON lock object.<br />
	 * This is added to the message document to signify that it was locked by this process.
	 */
	private JsonNode createLock(long visibilityTimeout) {
		ObjectNode lock = new ObjectNode(JsonNodeFactory.instance);
		lock.put("locked_at", System.currentTimeMillis());
		lock.put("locked_by", this.processId);
		lock.put("visibility_timeout", visibilityTimeout);
		return lock;
	}

	/**
	 * Attempts to lock the documents - bulk-update them with a "lock" field.<br />
	 * Returns the result of the bulk update. Some or all of the documents may not have been saved
	 * due to update conflict (some other process had already updated these docs).
	 */
	private ArrayList<JsonNode> lockDocuments(List<Document> docs, long visibilityTimeout) throws RQSException {
		// ensure that the bulk cache size is larger than the docs list, so that it won't flush early
		final int bulkSize = docs.size() << 1;
		if (db.getBulkUpdatesLimit() < bulkSize)
			db.setBulkUpdatesLimit(bulkSize);
		
		JsonNode lock = createLock(visibilityTimeout);
		try {
			for (Document doc : docs) {
				ObjectNode json = (ObjectNode) doc.getJson();
				json.put("lock", lock);
				db.saveInBulk(doc);
			}
			return db.flushBulkUpdatesCache(false, true);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Returns a list of Messages that encapsulate the given documents.
	 * </p>
	 * The lockResults JSONs contain message ids, that should match ids in docs, and an optional error.
	 * Get this list by calling {@link #lockDocuments(java.util.List, long) }.<br />
	 * This method only creates and returns Message object for docs that had no lock errors.
	 */
	/*
	 * This runs in O(n^2) time. I'm assuming that normally people won't retrieve lots of messages
	 * at once. Still, consider revising for long lists.
	 */
	private ArrayList<Message> createMessagesFromLockedDocs(List<Document> docs, List<JsonNode> lockResults) {
		ArrayList<Message> messages = new ArrayList<Message>();
		for (JsonNode res : lockResults) {
			if (res.get("error") != null)
				continue;
			String id = res.get("id").getTextValue();
			String rev = res.get("rev").getTextValue();
			Document doc = findDocById(docs, id);
			doc.setRev(rev);
			messages.add(new Message(doc));
		}
		return messages;
	}

	/**
	 * Return the first document in the list whose id is the same as the id specified, or null
	 * if no such document is found.
	 */
	private Document findDocById(List<Document> docs, String id) {
		for (Document doc : docs) {
			if (doc.getId().equals(id))
				return doc;
		}
		return null;
	}

	private List<Message> lockAndGetAttachments(List<Document> docs, long visibilityTimeout) throws RQSException {
		if (docs.isEmpty()) // no messages found
			return new ArrayList<Message>();
		List<JsonNode> lockResults = lockDocuments(docs, visibilityTimeout);
		// only use docs messages that were successfully locked
		ArrayList<Message> messages = createMessagesFromLockedDocs(docs, lockResults);
		// the message data is a document attachment, and must be retrieved separately
		for (Message message : messages) {
			byte[] data;
			try {
				data = db.getAttachment(message.getDoc().getId(), MESSAGE_ATTACHMENT_NAME);
			} catch (Exception e) {
				throw new RQSException(e);
			}
			message.setData(data);
		}
		return messages;
	}

	/**
	 * Attempts to retrieve pending messages from the queue.
	 * <p>
	 * Returns an empty list if there are no messages pending, or there are messages pending, but the
	 * attempt to lock them failed - probably because another process had locked the same messages first.
	 * In that case, the call should be attempted again after a while.<br />
	 * Note that this method only returns those messages for which a lock was acquired successfully.
	 */
	private List<Message> doReceiveMessages(int maxNumberOfMessages, long visibilityTimeout, boolean descending)
		throws RQSException
	{
		List<Document> docs = getPendingDocsFromView(RQS_PENDING_VIEW_NAME,
													 maxNumberOfMessages,
													 descending);
		return lockAndGetAttachments(docs, visibilityTimeout);
	}

	/**
	 * Retrieves up to maxNumberOfMessages messages from the queue's head (FIFO).<br />
	 * If there are no messages pending, returns an empty list.
	 *
	 * @param maxNumberOfMessages	maximum number of messages that will be retrieved
	 * @param visibilityTimeout		visibility timeout assigned to those messages. Overrides this queue's default
	 * @return	a list of messages for processing. The list may be empty but never null.
	 */
	public List<Message> receiveMessages(int maxNumberOfMessages, long visibilityTimeout) throws RQSException {
		return doReceiveMessages(maxNumberOfMessages, visibilityTimeout, false);
	}

	/**
	 * Retrieves up to maxNumberOfMessages messages from the queue's tail (LIFO).<br />
	 * Other than LIFO instead of FIFO, behaves the same as {@link #receiveMessages(int, long) }
	 */
	public List<Message> receiveMessagesFromTail(int maxNumberOfMessages, long visibilityTimeout) throws RQSException {
		return doReceiveMessages(maxNumberOfMessages, visibilityTimeout, true);
	}

	public List<Message> receiveMessages(int maxNumberOfMessages) throws RQSException {
		return receiveMessages(maxNumberOfMessages, this.visibilityTimeout);
	}

	public List<Message> receiveMessagesFromTail(int maxNumberOfMessages) throws RQSException {
		return receiveMessagesFromTail(maxNumberOfMessages, this.visibilityTimeout);
	}

	/**
	 * Retrieves arbitrary messages from the queue.
	 *
	 * @param messageIds	UUIDs of the message. They are returned in the same order.
	 * @param visibilityTimeout		visibility timeout assigned to those messages. Overrides this queue's default
	 * @return	a list of messages for processing. The list may be empty but never <code>null</code>.
	 */
	public List<Message> receiveMessages(List<String> messageIds, long visibilityTimeout) throws RQSException {
		for (String id : messageIds)
			System.out.println("messageId: " + id);
		List<Document> docs = getDocsFromDatabase(messageIds);
		return lockAndGetAttachments(docs, visibilityTimeout);
	}

	/**
	 * Retrieve a single message from the queue's head (FIFO).
	 *
	 * @param visibilityTimeout	visibility timeout assigned to the message. Overrides this queue's default
	 * @return	a message, or null if no pending messages were found
	 */
	public Message receiveMessage(long visibilityTimeout) throws RQSException {
		List<Message> list = receiveMessages(1, visibilityTimeout);
		if (list.isEmpty())
			return null;
		return list.get(0);
	}

	/**
	 * Retrieve a single message from the queue's tail (LIFO).
	 *
	 * @param visibilityTimeout	visibility timeout assigned to the message. Overrides this queue's default
	 * @return	a message, or null if no pending messages were found
	 */
	public Message receiveMessageFromTail(long visibilityTimeout) throws RQSException {
		List<Message> list = receiveMessagesFromTail(1, visibilityTimeout);
		if (list.isEmpty())
			return null;
		return list.get(0);
	}

	/**
	 * Retrieve an arbitrary message from the queue.
	 *
	 * @param messageId UUID of the message
	 * @param visibilityTimeout	visibility timeout assigned to the message. Overrides this queue's default
	 * @return	a message, or null if no pending messages were found
	 */
	public Message receiveMessage(String messageId, long visibilityTimeout) throws RQSException {
		try {
			Document doc = db.getDocumentOrNull(messageId);
			if (null == doc)
				return null;
			// lock the document
			ObjectNode json = (ObjectNode) doc.getJson();
			json.put("lock", createLock(visibilityTimeout));
			Document lockedDoc = db.updateDocument(doc);
			byte[] data;
			try {
				data = db.getAttachment(lockedDoc.getId(), MESSAGE_ATTACHMENT_NAME);
			} catch (Exception e) {
				throw new RQSException(e);
			}
			return new Message(lockedDoc, data);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	public Message receiveMessage() throws RQSException {
		return receiveMessage(this.visibilityTimeout);
	}

	public Message receiveMessageFromTail() throws RQSException {
		return receiveMessageFromTail(this.visibilityTimeout);
	}

	public Message receiveMessage(String messageId) throws RQSException {
		return receiveMessage(messageId, this.visibilityTimeout);
	}
	
	/**
	 * Delete the specified message from the queue.<br />
	 * Caller must be the owner of the lock on this message.
	 *
	 * @param messageId			UUID of the message
	 * @param receiptToken		the receipt token received through a call to {@link #receiveMessage()}
	 *
	 * @throws NoSuchMessageException			if there is no message in the queue with the specified messageId
	 * @throws ReceiptTokenOutOfDateException	if the receiptToken is no longer valid - probably because
	 * the original timeout was exceeded and another process got a lock on the message
	 * @throws RQSException					wraps any other exception thrown by the underlying CouchDB layer
	 */
	public void deleteMessage(String messageId, String receiptToken)
			throws NoSuchMessageException, ReceiptTokenOutOfDateException, RQSException
	{
		Document doc = new Document(messageId, receiptToken);
		try {
			db.deleteDocument(doc);
		} catch (CouchDBException cdbe) {
			if (cdbe.getStatusCode() == 404)
				throw new NoSuchMessageException("The queue has no message with ID " + messageId, cdbe);
			if (cdbe.getStatusCode() == 409)
				throw new ReceiptTokenOutOfDateException("The message was already acquired by another process", cdbe);
			else
				throw new RQSException(cdbe);
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Extend the visibility timeout of the specified message by the specified amount.<br />
	 * Caller must be the owner of the lock on this message.<br />
	 * Note that this action creates a new receipt token that must be used in future actions on the message.
	 *
	 * @param messageId			UUID of the message
	 * @param receiptToken		the receipt token received through a call to {@link #receiveMessage()}
	 * @param visibilityTimeout	the number of milliseconds by which the existing visibilityTimeout is extended
	 * @return a new receiptToken for the modified message
	 *
	 * @throws ReceiptTokenOutOfDateException	if the receiptToken is no longer valid - probably because
	 * the original timeout was exceeded and another process got a lock on the message
	 * @throws NoSuchMessageException			if there is no message in the queue with the specified messageId
	 * @throws RQSException					wraps any other exception thrown by the underlying CouchDB layer
	 */
	public String changeMessageVisibility(String messageId, String receiptToken, long visibilityTimeout)
			throws ReceiptTokenOutOfDateException, NoSuchMessageException, RQSException
	{
		Document doc = null;
		try {
			doc = db.getDocumentOrNull(messageId);
			if (doc == null)
				throw new NoSuchMessageException("The queue has no message with ID " + messageId);
			if (!doc.getRev().equals(receiptToken))
				throw new ReceiptTokenOutOfDateException("The message was already acquired by another process");
			ObjectNode lock = (ObjectNode) doc.getJson().get("lock");
			long current = lock.get("visibility_timeout").getLongValue();
			lock.put("visibility_timeout", current + visibilityTimeout);
			try {
				Document newDoc = db.updateDocument(doc);
				return newDoc.getRev();
			} catch (CouchDBException cdbe) {
				if (cdbe.getStatusCode() == 409) // update conflict
					throw new ReceiptTokenOutOfDateException("The message was already acquired by another process");
				else // rethrow
					throw cdbe;
			}
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	private int getNumberOfMessages(String viewName) throws RQSException {
		// use limit=0 to get just the view metadata, including total rows, but no actual rows
		List<NameValuePair> params = new ArrayList<NameValuePair>() {{
			add(new BasicNameValuePair("limit", "0"));
		}};
		try {
			JsonNode json = db.queryViewRaw(RQS_DESIGN_DOC_NAME, viewName, params);
			return json.get("total_rows").getIntValue();
		} catch (Exception e) {
			throw new RQSException(e);
		}
	}

	/**
	 * Returns the current number of pending messages in the queue.<br />
	 * This is the maximum number that can be received with {@link #receiveMessages(int)}
	 *
	 * @throws RQSException wraps any exception thrown by the underlying CouchDB layer
	 */
	public int numberOfMessagesPending() throws RQSException {
		return getNumberOfMessages(RQS_PENDING_VIEW_NAME);
	}

	/**
	 * Returns the current number of invisible (locked) messages in the queue.<br />
	 * These are messages that were received by various processes, but not yet deleted or returned to pending
	 * state due to timeout.
	 *
	 * @throws RQSException wraps any exception thrown by the underlying CouchDB layer
	 */
	public int numberOfMessagesNotVisible() throws RQSException {
		return getNumberOfMessages(RQS_LOCKED_VIEW_NAME);
	}

	@Override
	public String toString() {
		return String.format("Queue %s on %s", processId, db.toString());
	}

	/**
	 * Returns the status of the specified message.
	 *
	 * @see MessageStatus
	 *
	 * @throws RQSException wraps any exception thrown by the underlying CouchDB layer
	 */
	public MessageStatus getMessageStatus(String messageId) throws RQSException {
		Document doc;
		try {
			doc = db.getDocumentOrNull(messageId);
		} catch (Exception e) {
			throw new RQSException(e);
		}
		if (doc == null)
			return MessageStatus.MISSING;

		JsonNode lock;
		if ((lock = doc.getJson().get("lock")) != null) {
			MessageStatus locked = MessageStatus.LOCKED;
			locked.setProcessId(lock.get("locked_by").getTextValue());
			locked.setTimestamp(lock.get("visibility_timeout").getLongValue());
			return locked;
		}

		MessageStatus pending = MessageStatus.PENDING;
		pending.setTimestamp(doc.getJson().get("sent_at").getLongValue());
		return pending;
	}

	/**
	 * Describes the status of a message in the queue.<br />
	 * One of: <ul>
	 * <li>PENDING - the message is available for retrieval.</li>
	 * <li>LOCKED  - the message was retrieved by some process, but not deleted yet.</li>
	 * <li>MISSING - the message wasn't found in the queue. It may have been deleted already.</li>
	 * </ul>
	 * <p>
	 * For some statuses, more information is available - see {@link #getProcessId() } and {@link #getTimestamp() }
	 */
	public enum MessageStatus {
		PENDING,
		LOCKED,
		MISSING;

		private long timestamp = -1;
		private String processId = null;

		/**
		 * If <code>LOCKED</code>, returns the identifier of the owner of this lock.<br />
		 * Otherwise returns <code>null</code>
		 */
		public String getProcessId() {
			return processId;
		}

		void setProcessId(String processId) {
			this.processId = processId;
		}

		/**
		 * If <code>PENDING</code>, returns the timestamp when this message was sent to the queue.<br />
		 * If <code>LOCKED</code>, returns the timestamp when this message was locked.<br />
		 * Otherwise returns -1.
		 */
		public long getTimestamp() {
			return timestamp;
		}

		void setTimestamp(long timestamp) {
			this.timestamp = timestamp;
		}

	}

}

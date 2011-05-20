/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

import com.couchrqs.Queue.MessageStatus;
import com.jzboy.couchdb.CouchDBException;
import com.jzboy.couchdb.Document;
import java.util.ArrayList;
import java.util.List;
import org.codehaus.jackson.JsonNode;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the Queue class.
 */
public class QueueTest {

	static final String queueName = "rqs_queuetest_" + System.currentTimeMillis();
	static QueueService service;
	static Queue instance;

	@BeforeClass
	public static void setUpClass() throws Exception {
		service = new QueueService();
		instance = service.createQueue(queueName);
	}

	@AfterClass
	public static void tearDownClass() throws Exception {
	//	service.deleteQueue(queueName);
	}

	@Test
	public void testSendMessage() throws Exception {
		String attachment = "Test message for testSendMessage";
		byte[] data = attachment.getBytes();
		long t0 = System.currentTimeMillis();
		String messageId = instance.sendMessage(data);
		assertNotNull("Message id returned by sendMessage shouldn't be null", messageId);
		assertFalse("Message id returned by sendMessage shouldn't be an empty string", messageId.isEmpty());

		Document doc = instance.db.getDocumentOrNull(messageId);
		assertNotNull("Couch document wasn't created by sendMessage", doc);
		JsonNode sentAtNode = doc.getJson().get("sent_at");
		assertNotNull("Sent message should have a 'sent_at' field", sentAtNode);
		assertTrue("Sent message's 'sent_at' value isn't in the expected range,",
						(sentAtNode.getLongValue() > t0) && (sentAtNode.getLongValue() < System.currentTimeMillis()));
		assertNull("Newly sent messsage mustn't have a lock on it",
						doc.getJson().get("lock"));
		try {
			byte[] savedData = instance.db.getAttachment(messageId, Queue.MESSAGE_ATTACHMENT_NAME);
			String savedAttachment = new String(savedData);
			assertEquals("New message attachment doesn't equal the expected value",
							attachment, savedAttachment);
		} catch (CouchDBException e) {
			fail("Couldn't retrieve sent message: " + e.getMessage());
		}

	}

	@Test
	public void testReceiveArbitraryMessage() throws Exception {
		final long visibilityTimeout = 60000;
		Message msg = instance.receiveMessage("no-such-message-id", visibilityTimeout);
		assertNull("receiveMessage on a non-existent message should return null", msg);

		String attachment = "Test message for testReceiveMessage";
		byte[] data = attachment.getBytes();
		String messageId = instance.sendMessage(data);

		msg = instance.receiveMessage(messageId, visibilityTimeout);
		assertNotNull("receiveMessage on an existing message must not return null", msg);

		String receivedAttachment = new String(msg.getData());
		assertEquals("New message attachment doesn't equal the expected value,",
						attachment, receivedAttachment);
		// lock information isn't retrieved with the message so get it directly from Couch
		Document doc = instance.db.getDocumentOrNull(messageId);
		assertEquals("New message visibility timeout doesn't equal the expected value,",
						visibilityTimeout, doc.getJson().get("lock").get("visibility_timeout").getLongValue());
		assertEquals("New message locking process ID doesn't equal the expected value,",
						instance.getProcessId(), doc.getJson().get("lock").get("locked_by").getTextValue());
	}

	@Test
	public void testDeleteMessage() throws Exception {
		try {
			instance.deleteMessage("no-such-message-id", "no-such-receipt-token");
			fail("deleteMessage on a non-existent message should throw a NoSuchMessageException");
		} catch (NoSuchMessageException e) { }

		byte[] data = "Test message for testDeleteMessage".getBytes();
		String messageId = instance.sendMessage(data);
		
		Message msg = instance.receiveMessage(messageId);
		try {
			String wrongToken = msg.getReceiptToken() + "1";
			instance.deleteMessage(messageId, wrongToken);
			fail("deleteMessage with wrong receipt token should throw a ReceiptTokenOutOfDateException");
		} catch (ReceiptTokenOutOfDateException e) { }

		instance.deleteMessage(msg.getMessageId(), msg.getReceiptToken());
		assertNull("Presumably deleted message still exists in the queue!",
						instance.db.getDocumentOrNull(messageId));
	}

	@Test
	public void testChangeMessageVisibility() throws Exception {
		byte[] data = "Test message for testChangeMessageVisibility".getBytes();
		String messageId = instance.sendMessage(data);

		Message msg = instance.receiveMessage(messageId);
		Document doc = instance.db.getDocument(messageId);
		long origVis = doc.getJson().get("lock").get("visibility_timeout").getLongValue();
		long extension = 60000;

		String receiptToken = instance.changeMessageVisibility(messageId, msg.getReceiptToken(), extension);

		doc = instance.db.getDocument(messageId);
		long currentVis = doc.getJson().get("lock").get("visibility_timeout").getLongValue();
		assertEquals("Message visibility not modified as expected,", currentVis, origVis + extension);
		assertEquals("changeMessageVisibility didn't return the correct new receiptToken,",
						doc.getRev(), receiptToken);
	}

	@Test
	public void testGetMessageStatus() throws Exception {
		byte[] data = "Test message for testGetMessageStatus".getBytes();
		String messageId = instance.sendMessage(data);
		
		assertEquals("New message should have status 'PENDING',",
						MessageStatus.PENDING, instance.getMessageStatus(messageId));

		Message msg = instance.receiveMessage(messageId);
		assertEquals("Locked message should have status 'LOCKED',",
						MessageStatus.LOCKED, instance.getMessageStatus(messageId));

		instance.deleteMessage(msg.getMessageId(), msg.getReceiptToken());
		assertEquals("Deleted message should have status 'MISSING',",
						MessageStatus.MISSING, instance.getMessageStatus(messageId));
	}

	
	@Test
	public void testNumberOfMessagesPending() throws Exception {
		int n0 = instance.numberOfMessagesPending();
		final int numNew = 3;
		List<String> messageIds = sendNMessages(numNew);
		int n1 = instance.numberOfMessagesPending();
		assertEquals("Number of pending messages not as expected after sending some messages,",
						n0 + numNew, n1);

		instance.receiveMessage(messageIds.get(0));
		int n2 = instance.numberOfMessagesPending();
		assertEquals("Number of pending messages not as expected after locking some messages,",
						n0 + numNew - 1, n2);
	}

	@Test
	public void testNumberOfMessagesNotVisible() throws Exception {
		int n0 = instance.numberOfMessagesNotVisible();
		List<String> messageIds = sendNMessages(3);
		int n1 = instance.numberOfMessagesNotVisible();
		assertEquals("Number of invisible messages not as expected - affected by *sending*,",
						n0, n1);

		instance.receiveMessage(messageIds.get(0));
		int n2 = instance.numberOfMessagesNotVisible();
		assertEquals("Number of invisible messages not as expected after locking a message,",
						n0 + 1, n2);
	}

	@Test
	public void testReceiveMessages() throws Exception {
		int numPending = instance.numberOfMessagesPending();
		if (numPending > 0)
			instance.receiveMessages(numPending);
		numPending = instance.numberOfMessagesPending();
		assertEquals("Number of pending messages not zero-ed out after receiving them,",
						0, numPending);

		final int numNew = 5;
		List<String> messageIds = sendNMessages(numNew);
		final int numToGet = 3;
		List<Message> messages = instance.receiveMessages(numToGet);
		assertEquals("Failed to receive requested number of messages,",
						numToGet, messages.size());
		for (int i = 0; i < messages.size(); i++) {
			assertEquals("Message ID not same as expected,",
							messageIds.get(i), messages.get(i).getMessageId());
		}

		messages = instance.receiveMessages(numToGet);
		assertEquals("Number of received messages doesn't match the expected number,",
						numNew - numToGet, messages.size());
	}

	@Test
	public void testReceiveMessagesFromTail() throws Exception {
		final int numNew = 5;
		List<String> messageIds = sendNMessages(numNew);
		final int numToGet = 3;
		List<Message> messages = instance.receiveMessagesFromTail(numToGet);
		assertEquals("Failed to receive requested number of messages,",
						numToGet, messages.size());

		for (int i = 0; i < numToGet; i++) {
			assertEquals("Message ID not same as expected,",
							messageIds.get(numNew - i - 1), messages.get(i).getMessageId());
		}
	}

	private List<String> sendNMessages(int n) throws RQSException {
		List<String> messageIds = new ArrayList<String>();
		for (int i = 0; i < n; i++) {
			byte[] data = String.format("Test message %d for testNumberOfMessagesPending", i).getBytes();
			String messageId = instance.sendMessage(data);
			messageIds.add(messageId);
		}
		return messageIds;
	}

}
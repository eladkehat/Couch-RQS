/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

import com.jzboy.couchdb.Database;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the QueueService class.
 */
public class QueueServiceTest {

	static QueueService instance;

	final static String queueName = "rqs_queueservicetest_" + System.currentTimeMillis();
	final static String nonQueueName = "rqs_non_queue_database_" + System.currentTimeMillis();
	final static String nonExistingQueue = "rqs_non_existing_queue_" + System.currentTimeMillis();

	static Database nonQueue;
	static Queue queue;

	@BeforeClass
	public static void setUpClass() throws Exception {
		instance = new QueueService();
		queue = instance.createQueue(queueName);
		nonQueue = new Database(instance.couchDB, nonQueueName);
		nonQueue.create();
	}

	@AfterClass
	public static void tearDownClass() {
		try {
			nonQueue.delete();
			new Database(instance.couchDB, queueName).delete();
		} catch (Exception e) {
			System.err.println("Caught " + e + " during QueueServiceTest.tearDownClass");
			e.printStackTrace(System.err);
		}
	}

	@Test
	public void testCreateQueue() throws Exception {
		final String newQueueName = "rqs_queueservicetest_createqueue_" + System.currentTimeMillis();
		assertFalse("Supposedly non-existent queue already exists!",
				new Database(instance.couchDB, newQueueName).exists());
		try {
			Queue newQueue = instance.createQueue(newQueueName);
			assertEquals("Newly created queue should have the name it was created with",
					newQueueName, newQueue.getName());
			assertTrue("Newly created queue should create a CouchDB Database", newQueue.db.exists());

			try {
				instance.createQueue(newQueueName);
				fail("Creation of a duplicate queue should throw a QueueNameAlreadyTakenException");
			} catch (QueueNameAlreadyTakenException qnate) { }
		} finally {
			new Database(instance.couchDB, newQueueName).delete();
		}
	}

	@Test
	public void testIsQueue() throws Exception {
		assertFalse("Non-existent queue should respond 'false' to isQueue", instance.isQueue(nonExistingQueue));
		assertTrue("Initialized queue should respond 'true' to isQueue", instance.isQueue(queueName));
		assertFalse("Non-queue database should respond 'false' to isQueue", instance.isQueue(nonQueueName));
	}

	@Test
	public void testGetQueue() throws Exception {
		try {
			instance.getQueue(nonExistingQueue);
			fail("getQueue on non-existent queue should throw a NoSuchQueueException");
		} catch (NoSuchQueueException nsqe) { }

		Queue queue2 = instance.getQueue(queueName);
		assertEquals(queue.getName(), queue2.getName());
		assertEquals(queue.getProcessId(), queue2.getProcessId());
	}

	@Test
	public void testDeleteQueue() throws Exception {
		Database db = new Database(instance.couchDB, nonExistingQueue);
		assertFalse("Supposedly non-existent queue already exists!", db.exists());
		assertFalse("deleteQueue should return 'false' on non-existent queue", instance.deleteQueue(nonExistingQueue));

		assertFalse("deleteQueue should return 'false' on non-queue database", instance.deleteQueue(nonQueueName));

		final String delQueueName = "queue-service-test" + System.currentTimeMillis();
		db = new Database(instance.couchDB, delQueueName);
		assertFalse("Supposedly non-existent queue already exists!", db.exists());
		instance.createQueue(delQueueName);
		assertTrue("Newly created queue doesn't exist", db.exists());
		Thread.sleep(250);
		assertTrue("deleteQueue should return 'true' when called existing queue",
				instance.deleteQueue(delQueueName));
		assertFalse("Deleted queue still exists!", db.exists());
	}

	@Test
	public void testListQueues() throws Exception {
		final String queue2Name = "rqs_queueservicetest_listqueues2_" + System.currentTimeMillis();
		try {
			instance.createQueue(queue2Name);

			List<String> queueNames = instance.listQueues();
			assertEquals("Expected list of queue names to contain 2 names", 2, queueNames.size());
			assertTrue("Expected list of queue names to contain " + queueName, queueNames.contains(queueName));
			assertTrue("Expected list of queue names to contain " + queue2Name, queueNames.contains(queue2Name));
		} finally {
			new Database(instance.couchDB, queue2Name).delete();
		}
	}

	@Test
	public void testGetOrCreateQueue() throws Exception {
		Queue queue2 = instance.getOrCreateQueue(queueName);
		assertEquals(queue.getName(), queue2.getName());
		assertEquals(queue.getProcessId(), queue2.getProcessId());

		final String newQueueName = "rqs_queueservicetest_getorcreatequeue_" + System.currentTimeMillis();
		assertFalse("Supposedly non-existent queue already exists!",
				new Database(instance.couchDB, newQueueName).exists());
		try {
			Queue newQueue = instance.createQueue(newQueueName);
			assertEquals("Newly created queue should have the name it was created with",
					newQueueName, newQueue.getName());
			assertTrue("Newly created queue should create a CouchDB Database", newQueue.db.exists());
		} finally {
			new Database(instance.couchDB, newQueueName).delete();
		}

		try {
			instance.getOrCreateQueue(nonQueueName);
			fail("getOrCreateQueue should throw an exception when given the name of a non-queue database");
		} catch (QueueNameAlreadyTakenException qnate) { }
	}

}
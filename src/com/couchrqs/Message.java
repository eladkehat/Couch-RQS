/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

import com.jzboy.couchdb.Document;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;

/**
 * A RQS message.
 */
public class Message {

	final Document doc;
	byte[] data;

	public Message() {
		ObjectNode json = new ObjectNode(JsonNodeFactory.instance);
		json.put("sent_at", System.currentTimeMillis());
		doc = new Document(json);
		data = null;
	}

	public Message(Document doc, byte[] data) {
		this.doc = doc;
		this.data = data;
	}

	public Message(Document doc) {
		this.doc = doc;
		this.data = null;
	}

	public byte[] getData() {
		return data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

	public Document getDoc() {
		return doc;
	}

	public String getMessageId() {
		return doc.getId();
	}

	public String getReceiptToken() {
		return doc.getRev();
	}

	public long getSentTimestamp() {
		return doc.getJson().get("sent_at").getLongValue();
	}

	public JsonNode getLock() {
		return doc.getJson().get("lock");
	}

	public long getVisibilityTimeout() {
		JsonNode lock = getLock();
		if (lock != null)
			return lock.get("visibility_timeout").getLongValue();
		return 0l;
	}


}

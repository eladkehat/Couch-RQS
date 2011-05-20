/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

/**
 * Signals that an attempt to run some operation against a queue failed because a queue with that name
 * does not exist on the server.
 */
public class NoSuchQueueException extends RQSException {

	public NoSuchQueueException(String message) {
		super(message);
	}

}

/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

/**
 * Signals that an attempt to perform an action on a message failed because the message identified by that
 * action no longer exists in the queue.
 */
public class NoSuchMessageException extends RQSException {

	public NoSuchMessageException(String message) {
		super(message);
	}

	public NoSuchMessageException(String message, Exception cause) {
		super(message, cause);
	}

}

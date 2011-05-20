/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

/**
 * Signals that an attempt to create a queue failed because a queue with that name already exists on the server.
 * <p>
 * The caller can solve this situation by trying another queue name, using the already existing queue, or (provided
 * that it is safe to do so) delete the existing queue and then create a new one.
 */
public class QueueNameAlreadyTakenException extends RQSException {

	public QueueNameAlreadyTakenException(String message) {
		super(message);
	}

}

/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

/**
 * Signals that an attempt to delete a message failed because the receipt token supplied is no longer
 * the latest version. This usually means that the message was unlocked and acquired by another process
 * since the requesting process had last acquired it.
 */
public class ReceiptTokenOutOfDateException extends RQSException {

	public ReceiptTokenOutOfDateException(String message) {
		super(message);
	}

	public ReceiptTokenOutOfDateException(String message, Exception cause) {
		super(message, cause);
	}

}

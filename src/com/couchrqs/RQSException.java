/*
 * Copyright (c) 2011. Elad Kehat.
 * This software is provided under the MIT License:
 * http://www.opensource.org/licenses/mit-license.php
 */

package com.couchrqs;

/**
 * A parent class for checked exceptions related to the RQS code.
 */
public class RQSException extends Exception {

	public RQSException(String message) {
		super(message);
	}

	public RQSException(String message, Exception cause) {
		super(message, cause);
	}

	public RQSException(Exception cause) {
		super(cause);
	}

}

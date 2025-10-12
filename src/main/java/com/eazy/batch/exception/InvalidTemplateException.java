/*
 * #=============================================================================
 *  # Copyright (c) 2024 EBest Solutions Pvt. Ltd. All rights reserved.
 *  #
 *  # This software is furnished under a license and may be used and copied
 *  # only in accordance with the terms of such license and with the
 *  # inclusion of the above copyright notice. This software or any other
 *  # copies thereof may not be provided or otherwise made available to any
 *  # other person. No title to and ownership of the software is hereby transferred.
 *  #
 *  # The information in this software is subject to change without notice
 *  # and should not be construed as a commitment by EBest Solutions Pvt. Ltd.
 *  # EBest Solutions assumes no responsibility for the use or reliability of its
 *  # software on equipment, which is not supplied by EBest Solutions Pvt. Ltd.
 *  #=============================================================================
 */

package com.eazy.batch.exception;

import java.io.Serial;

public  class InvalidTemplateException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -1245783956296361733L;

    public InvalidTemplateException(String message) {
        super(message);
    }
}

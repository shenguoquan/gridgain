/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache;

/**
 * Thrown when an entry could not be redeployed.
 */
public class GridCacheEntryRedeployException extends Exception {
    private static final long serialVersionUID = -3505291588009018625L;

    /**
     * Creates exception with error message.
     *
     * @param msg Error message.
     */
    public GridCacheEntryRedeployException(String msg) {
        super(msg);
    }
}
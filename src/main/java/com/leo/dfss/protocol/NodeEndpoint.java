package com.leo.dfss.protocol;

/** Where a storage Node can be reached for chunk traffic. */
public record NodeEndpoint(String nodeId, String host, int port) {
    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}

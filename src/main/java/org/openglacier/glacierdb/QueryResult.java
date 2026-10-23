package org.openglacier.glacierdb;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** The documents and statistics of one query. */
public record QueryResult(List<Map<String, Object>> documents, Map<String, Object> statistics, long requestId)
        implements Iterable<Map<String, Object>> {
    public int size() { return documents.size(); }
    public boolean isEmpty() { return documents.isEmpty(); }
    public Map<String, Object> get(int index) { return documents.get(index); }
    @Override public Iterator<Map<String, Object>> iterator() { return documents.iterator(); }
}

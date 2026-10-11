package com.scb.monitoring.svcctl.dto;

import java.util.ArrayList;
import java.util.List;

/** service.list 가져오기 결과 */
public class ImportResult {

    private int added;
    private int updated;
    private int removed;
    private int total;
    private final List<String> skipped = new ArrayList<>();

    public void added() { added++; }
    public void updated() { updated++; }
    public void removed() { removed++; }
    public void setTotal(int total) { this.total = total; }
    public void skip(String reason) { skipped.add(reason); }

    public int getAdded() { return added; }
    public int getUpdated() { return updated; }
    public int getRemoved() { return removed; }
    public int getTotal() { return total; }
    public List<String> getSkipped() { return skipped; }
}

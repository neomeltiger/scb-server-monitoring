package com.scb.monitoring.topology.domain;

/** tb_net_node : 네트워크 구성도의 Hub / Switch 노드 */
public class NetNode {

    public static final String TYPE_HUB = "HUB";
    public static final String TYPE_SWITCH = "SWITCH";

    private Integer nodeId;
    private String nodeType;
    private String nodeNm;
    private String nodeDesc;
    private String siteCd;
    private String locationNm;
    private Integer parentNodeId;
    private Integer sortOrd;

    public boolean isSwitch() { return TYPE_SWITCH.equals(nodeType); }

    public Integer getNodeId() { return nodeId; }
    public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getNodeNm() { return nodeNm; }
    public void setNodeNm(String nodeNm) { this.nodeNm = nodeNm; }
    public String getNodeDesc() { return nodeDesc; }
    public void setNodeDesc(String nodeDesc) { this.nodeDesc = nodeDesc; }
    public String getSiteCd() { return siteCd; }
    public void setSiteCd(String siteCd) { this.siteCd = siteCd; }
    public String getLocationNm() { return locationNm; }
    public void setLocationNm(String locationNm) { this.locationNm = locationNm; }
    public Integer getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(Integer parentNodeId) { this.parentNodeId = parentNodeId; }
    public Integer getSortOrd() { return sortOrd; }
    public void setSortOrd(Integer sortOrd) { this.sortOrd = sortOrd; }
}

package com.scb.monitoring.topology.dto;

import com.scb.monitoring.topology.domain.NetNode;

/** 네트워크 노드 응답 (Hub / Switch) */
public class NodeView {

    private Integer id;
    private String type;
    private String name;
    private String desc;
    private String siteCd;
    private String location;
    private Integer parentId;

    public static NodeView of(NetNode n) {
        NodeView v = new NodeView();
        v.id = n.getNodeId();
        v.type = n.getNodeType();
        v.name = n.getNodeNm();
        v.desc = n.getNodeDesc();
        v.siteCd = n.getSiteCd();
        v.location = n.getLocationNm();
        v.parentId = n.getParentNodeId();
        return v;
    }

    public Integer getId() { return id; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getDesc() { return desc; }
    public String getSiteCd() { return siteCd; }
    public String getLocation() { return location; }
    public Integer getParentId() { return parentId; }
}

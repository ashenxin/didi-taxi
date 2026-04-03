package com.sx.adminapi.client.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class PassengerMenuNodeData {

    private Long id;

    private Long parentId;

    private String path;

    private String name;

    private String icon;

    private String component;

    private String perms;

    private int sort;

    private boolean visible;

    private List<PassengerMenuNodeData> children = new ArrayList<>();
}

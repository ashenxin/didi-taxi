package com.sx.passenger.admin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class AdminMenuNodeResponse {

    private Long id;

    private Long parentId;

    private String path;

    private String name;

    private String icon;

    private String component;

    private String perms;

    private int sort;

    private boolean visible;

    private List<AdminMenuNodeResponse> children = new ArrayList<>();
}

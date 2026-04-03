package com.sx.adminapi.model.auth;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class AdminMenuNodeVO {

    private Long id;

    private Long parentId;

    private String path;

    private String name;

    private String icon;

    private String component;

    private String perms;

    private int sort;

    private boolean visible;

    private List<AdminMenuNodeVO> children = new ArrayList<>();
}

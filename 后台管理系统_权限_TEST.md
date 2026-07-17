# 后台管理系统：权限与鉴权 TEST

本文覆盖管理端登录、菜单、管理员账号和跨业务数据域。订单、运力、计价的业务字段测试见各自专项 TEST。

## 0. 环境与账号

- 启动 gateway、admin-api、passenger、Redis、MySQL。
- 准备角色：SUPER、浙江省管、杭州市操作员、宁波市操作员、禁用账号。
- 各账号配置明确 `provinceCode/cityCode`，并准备浙江/其它省、杭州/宁波资源。
- 所有请求经 `/admin/**`，不直接信任客户端 `X-User-Id`。

## 1. 登录和 JWT

### T-ADMIN-AUTH-01 正常登录

调用 `POST /admin/api/v1/auth/login`。

预期：返回 `aud=admin-bff`、`sub=sys_user.id`、含角色和数据域 claims 的 token；密码 hash 不返回。

### T-ADMIN-AUTH-02 错误密码与不可用账号

预期：返回 401/明确业务错误；不签发 token；错误不能泄露账号是否为 SUPER、密码 hash 或内部表结构。

### T-ADMIN-AUTH-03 错端和伪造身份

用乘客/司机 token 访问 `/admin/**`，以及用 admin token 传伪造 `X-User-Id`。

预期：错端 token 被网关拒绝；伪造头被覆盖，审计操作者始终是 JWT sub。

### T-ADMIN-AUTH-04 `/me`

预期：返回当前账号、显示名、角色、数据域；不返回密码、tokenVersion 等敏感内部字段。

## 2. 菜单

### T-ADMIN-MENU-01 SUPER 菜单

调用 `/admin/api/v1/auth/menus`。

预期：返回全部有效菜单，父子层级和排序稳定；禁用/逻辑删除菜单不出现。

### T-ADMIN-MENU-02 非 SUPER 菜单

分别用省管、市操作员查询。

预期：只返回角色允许菜单；父菜单在有可见子菜单时保留；无权限按钮不下发。

### T-ADMIN-MENU-03 菜单与接口权限一致

隐藏某业务菜单后直接调用对应接口。

预期：若系统实现接口权限串，应同时拒绝；至少不得仅靠前端隐藏保护高风险写接口。

## 3. 通用数据域

### T-ADMIN-SCOPE-01 SUPER

不传省市、传任意合法省市查询订单/运力/计价。

预期：SUPER 可跨域；请求筛选生效。

### T-ADMIN-SCOPE-02 省管

浙江省管分别查询全省、杭州、宁波和其它省。

预期：省内成功；其它省筛选 403；不传省时服务端自动限制浙江，不能返回外省资源。

### T-ADMIN-SCOPE-03 市操作员

杭州市操作员不传城市、传杭州、传宁波。

预期：前两者只返回杭州；宁波返回 403。

### T-ADMIN-SCOPE-04 资源掩蔽

用杭州账号直接访问宁波订单、司机、车辆、计价规则、优惠券模板详情。

预期：返回 404，不暴露它域资源存在性。

### T-ADMIN-SCOPE-05 分页 total 不泄露

非 SUPER 查询列表。

预期：total 是数据域裁剪后的数量，不得通过总数泄露其它城市资源。

## 4. 管理员账号 CRUD

### T-ADMIN-STAFF-01 分页与详情

验证 `/admin/api/v1/system/admin-users` 查询、分页、角色/状态筛选；详情不返回密码 hash。

### T-ADMIN-STAFF-02 SUPER 创建账号

创建省管和市操作员。

预期：用户名唯一；角色与省市组合合法；密码按安全哈希存储；创建者取登录账号。

### T-ADMIN-STAFF-03 省管创建账号

浙江省管创建杭州操作员、其它省管理员、SUPER。

预期：仅自身数据域内允许；不得创建 SUPER 或越级账号。

### T-ADMIN-STAFF-04 市操作员越权

调用管理员 CRUD。

预期：403；不能通过构造 body 绕过角色限制。

### T-ADMIN-STAFF-05 修改和删除

验证角色、数据域、状态修改及逻辑删除。

预期：不可把账号改到操作者权限之外；删除后不能登录；历史审计仍能关联用户 ID。

### T-ADMIN-STAFF-06 并发唯一性

并发创建相同用户名。

预期：仅一个成功；唯一键冲突转换为明确 409，不返回 SQL 异常。

## 5. 会话和安全

### T-ADMIN-SEC-01 token 失效

禁用/删除管理员或推进 tokenVersion 后使用旧 token。

预期：按当前 admin 鉴权实现失效；若某层只校验 JWT exp，应记录为安全差距，不能误判通过。

### T-ADMIN-SEC-02 修改密码接口

当前未实现 `PUT /admin/api/v1/auth/password`，不执行成功用例；请求该路径应 404/405，而不是假成功。

### T-ADMIN-SEC-03 敏感字段和错误

检查登录失败、下游 5xx、越权响应。

预期：不泄露密码、密钥、SQL、其它域资源详情；403/404 语义稳定。

## 6. 自动化与证据

当前 admin-api 缺系统化自动测试。至少保存不同角色的 claims、列表筛选条件/total、越权响应和资源所属省市数据库证据。

## 7. 2026-07-17 实测结果

- 使用 SUPER 账号通过真实登录页进入管理后台，菜单加载正常。
- `/admin/api/v1/system/admin-users` 实际返回 4 条记录，页面显示“共 4 条”；此前“列表 4 条但 total=0”的问题已修复。
- 根因是 passenger 服务缺少 MyBatis-Plus `PaginationInnerInterceptor`；已补充配置和回归测试。
- `mvn -pl passenger test`：2 个测试通过；`mvn -pl admin-api test`：7 个测试通过。
- 本次只验证 SUPER 主路径和分页总数，不替代省管理员、市操作员的数据域/越权矩阵用例。

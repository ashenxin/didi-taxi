# 项目 Skills

本目录存放后端仓库的通用 [Agent Skills](https://docs.claude.com/en/docs/agents-and-tools/agent-skills)。
一个 skill 是一个目录，包含 `SKILL.md`（YAML frontmatter + Markdown 说明）以及可选的
`references/`、`scripts/`、`assets/`。Skill 是给 agent 的说明，不是运行时服务。

## 目录结构

```text
skills/
└── weather-query/
    ├── SKILL.md
    ├── references/
    │   ├── contract.md
    │   └── verification.md
    └── scripts/
        └── forecast.sh
```

`SKILL.md` 保持简短、可执行；详细契约和测试矩阵放到 `references/`，由主文件按需链接，
避免每次加载全部内容。

## 语言约定

- `SKILL.md` 正文和 `references/` 使用**中文**说明。
- frontmatter 的 `name` 保持英文小写连字符，并与目录名一致。
- `description` 用中文，但保留关键英文标识（如 `WEATHER_QUERY`、`AmapWeatherService`、
  `WeatherAnswerFormatter`），便于触发匹配。
- 代码块、类名、路径、命令、JSON 字段、状态码和错误码保持英文，不翻译。
- 不要把密钥、token 或特定环境的凭据写进 skill。

## Frontmatter

```yaml
---
name: weather-query
description: >-
  说明这个 skill 做什么、什么时候使用；保留关键英文关键词。
---
```

- `name`：小写字母、数字和连字符；必须和目录名一致。
- `description`：同时写能力和触发条件，控制在 1024 字符以内。

## 安装与发现

不同 agent 的项目 skill 目录不同，可选：

```bash
# Claude Code
mkdir -p .claude/skills
ln -s ../../skills/weather-query .claude/skills/weather-query

# Cursor
mkdir -p .cursor/skills
ln -s ../../skills/weather-query .cursor/skills/weather-query
```

也可以直接复制目录。前端仓库已有 `.cursor/skills/vant/SKILL.md`；每个 skill 只保留一份
权威副本，避免多处修改分叉。

## 当前 Skills

| Skill | 用途 |
|---|---|
| `weather-query` | 开发、测试和扩展乘客端 AI 客服天气查询能力 |

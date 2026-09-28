package com.jarves.mh.mcp

import java.util.UUID

enum class McpTransportType {
    STDIO,
    SSE,
    HTTP,
}

data class McpTool(
    val id: String = UUID.randomUUID().toString(),
    val serverId: String,
    val name: String,
    val displayName: String,
    val description: String,
    val inputSchema: String = "{}",
)

data class McpServerConfig(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val transportType: McpTransportType = McpTransportType.STDIO,
    val serverUrl: String? = null,
    val category: String,
    val iconEmoji: String,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = true,
    val isOnline: Boolean = true,
    val tools: List<McpTool> = emptyList(),
)

data class McpHook(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val triggerKeywords: List<String>,
    val targetServerId: String,
    val isEnabled: Boolean = true,
    val autoTriggered: Boolean = true,
)

data class McpExecutionResult(
    val toolName: String,
    val isSuccess: Boolean,
    val output: String,
    val error: String? = null,
)

fun defaultMcpServers(): List<McpServerConfig> = listOf(
    McpServerConfig(
        id = "mcp-github",
        name = "GitHub MCP",
        description = "Direct GitHub repository search, issues, commits, branches, and PR management",
        command = "npx -y @modelcontextprotocol/server-github",
        category = "Code & Version Control",
        iconEmoji = "🐙",
        isEnabled = true,
        tools = listOf(
            McpTool(serverId = "mcp-github", name = "search_repositories", displayName = "Search Repositories", description = "Find GitHub repositories matching query"),
            McpTool(serverId = "mcp-github", name = "get_file_contents", displayName = "Get File Contents", description = "Read files directly from GitHub branches"),
            McpTool(serverId = "mcp-github", name = "create_or_update_file", displayName = "Create/Update File", description = "Push commits and file changes directly"),
            McpTool(serverId = "mcp-github", name = "create_issue", displayName = "Create Issue", description = "File bugs and task issues"),
            McpTool(serverId = "mcp-github", name = "create_pull_request", displayName = "Create Pull Request", description = "Open pull requests on GitHub"),
        ),
    ),
    McpServerConfig(
        id = "mcp-filesystem",
        name = "Filesystem MCP",
        description = "Universal filesystem tool for reading, writing, searching, and managing project files",
        command = "npx -y @modelcontextprotocol/server-filesystem /workspace",
        category = "System & Files",
        iconEmoji = "📁",
        isEnabled = true,
        tools = listOf(
            McpTool(serverId = "mcp-filesystem", name = "read_file", displayName = "Read File", description = "Read complete file content safely"),
            McpTool(serverId = "mcp-filesystem", name = "write_file", displayName = "Write File", description = "Write or create project files"),
            McpTool(serverId = "mcp-filesystem", name = "list_directory", displayName = "List Directory", description = "Browse folder contents"),
            McpTool(serverId = "mcp-filesystem", name = "search_files", displayName = "Search Files", description = "Fast regex and glob file matching"),
        ),
    ),
    McpServerConfig(
        id = "mcp-sqlite",
        name = "SQLite MCP",
        description = "Embedded relational database query runner, schema introspection, and table inspector",
        command = "npx -y @modelcontextprotocol/server-sqlite",
        category = "Databases",
        iconEmoji = "🗄️",
        isEnabled = true,
        tools = listOf(
            McpTool(serverId = "mcp-sqlite", name = "read_query", displayName = "Run SELECT Query", description = "Execute read-only SQL queries"),
            McpTool(serverId = "mcp-sqlite", name = "write_query", displayName = "Execute DDL/DML", description = "Run insert, update, create table queries"),
            McpTool(serverId = "mcp-sqlite", name = "describe_table", displayName = "Describe Table", description = "Show table schema and columns"),
            McpTool(serverId = "mcp-sqlite", name = "list_tables", displayName = "List Tables", description = "Enumerate all database tables"),
        ),
    ),
    McpServerConfig(
        id = "mcp-brave-search",
        name = "Brave Web Search MCP",
        description = "Real-time internet web search for live documentation, APIs, and stackoverflow answers",
        command = "npx -y @modelcontextprotocol/server-brave-search",
        category = "Web & Search",
        iconEmoji = "🔍",
        isEnabled = true,
        tools = listOf(
            McpTool(serverId = "mcp-brave-search", name = "brave_web_search", displayName = "Web Search", description = "Execute web search query for current docs and answers"),
            McpTool(serverId = "mcp-brave-search", name = "brave_local_search", displayName = "Local Search", description = "Search places, addresses, and local services"),
        ),
    ),
    McpServerConfig(
        id = "mcp-fetch",
        name = "Fetch & Scraper MCP",
        description = "High-speed URL content fetcher and HTML to clean Markdown transformer",
        command = "npx -y @modelcontextprotocol/server-fetch",
        category = "Web & Search",
        iconEmoji = "🌐",
        isEnabled = true,
        tools = listOf(
            McpTool(serverId = "mcp-fetch", name = "fetch_url", displayName = "Fetch URL", description = "Retrieve web page text, JSON, or XML"),
            McpTool(serverId = "mcp-fetch", name = "convert_to_markdown", displayName = "HTML to Markdown", description = "Convert webpage HTML to readable Markdown"),
        ),
    ),
    McpServerConfig(
        id = "mcp-memory",
        name = "Memory Graph MCP",
        description = "Persistent semantic knowledge graph for user preferences, project context, and memories",
        command = "npx -y @modelcontextprotocol/server-memory",
        category = "Agentic Memory",
        iconEmoji = "🧠",
        isEnabled = true,
        tools = listOf(
            McpTool(serverId = "mcp-memory", name = "create_entities", displayName = "Store Entities", description = "Record knowledge graph nodes"),
            McpTool(serverId = "mcp-memory", name = "create_relations", displayName = "Create Relations", description = "Link concepts and project entities"),
            McpTool(serverId = "mcp-memory", name = "search_nodes", displayName = "Search Knowledge", description = "Semantic search across persistent graph memory"),
        ),
    ),
    McpServerConfig(
        id = "mcp-postgres",
        name = "PostgreSQL MCP",
        description = "Enterprise PostgreSQL database connectivity for querying remote and local databases",
        command = "npx -y @modelcontextprotocol/server-postgres",
        category = "Databases",
        iconEmoji = "🐘",
        isEnabled = false,
        tools = listOf(
            McpTool(serverId = "mcp-postgres", name = "query", displayName = "Postgres Query", description = "Execute PostgreSQL queries"),
            McpTool(serverId = "mcp-postgres", name = "describe_table", displayName = "Describe Schema", description = "Inspect PostgreSQL schema and indexes"),
        ),
    ),
    McpServerConfig(
        id = "mcp-puppeteer",
        name = "Puppeteer Browser MCP",
        description = "Headless Chromium browser automation for web scraping, screenshots, and UI testing",
        command = "npx -y @modelcontextprotocol/server-puppeteer",
        category = "Automation",
        iconEmoji = "🎭",
        isEnabled = false,
        tools = listOf(
            McpTool(serverId = "mcp-puppeteer", name = "navigate", displayName = "Navigate URL", description = "Browse to web page in headless Chromium"),
            McpTool(serverId = "mcp-puppeteer", name = "screenshot", displayName = "Capture Screenshot", description = "Take full page or element screenshot"),
            McpTool(serverId = "mcp-puppeteer", name = "click", displayName = "Click Element", description = "Click on web UI button or link"),
        ),
    ),
)

fun defaultMcpHooks(): List<McpHook> = listOf(
    McpHook(
        title = "GitHub Repo & Code Hook",
        description = "Auto-connects GitHub MCP whenever repositories, PRs, or issues are requested",
        triggerKeywords = listOf("github", "repo", "repository", "pull request", "pr", "commit", "clone git", "push commit"),
        targetServerId = "mcp-github",
    ),
    McpHook(
        title = "Database & SQL Hook",
        description = "Auto-connects SQLite MCP when SQL queries, tables, or database tasks are mentioned",
        triggerKeywords = listOf("sql", "sqlite", "query", "database", "table", "schema", "sqlite3", "db"),
        targetServerId = "mcp-sqlite",
    ),
    McpHook(
        title = "Web Search & Research Hook",
        description = "Auto-connects Brave Search when internet search, current info, or documentation lookup is needed",
        triggerKeywords = listOf("search", "google", "brave", "lookup", "browse", "internet", "web search", "find online"),
        targetServerId = "mcp-brave-search",
    ),
    McpHook(
        title = "Web Fetch & Scrape Hook",
        description = "Auto-connects Fetch MCP when URLs or web content extraction is requested",
        triggerKeywords = listOf("fetch", "scrape", "http://", "https://", "download url", "read url", "webpage"),
        targetServerId = "mcp-fetch",
    ),
    McpHook(
        title = "Agent Long-Term Memory Hook",
        description = "Auto-connects Memory Graph MCP to remember project patterns and user instructions",
        triggerKeywords = listOf("remember", "memory", "recall", "knowledge", "graph", "store context"),
        targetServerId = "mcp-memory",
    ),
    McpHook(
        title = "Autonomous Bug Fix & Self-Heal Hook",
        description = "Auto-activates deep diagnostic and self-healing loop when errors or bugs are reported",
        triggerKeywords = listOf("bug", "fix", "error", "crash", "exception", "failed", "repair", "issue", "problem", "solve"),
        targetServerId = "mcp-filesystem",
    ),
)

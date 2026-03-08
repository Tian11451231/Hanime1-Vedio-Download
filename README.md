# Hanime Media Center hanime1视频观看下载器

## 1. 项目怎么使用

### 方式一：直接使用单文件 EXE

1. 双击 HanimeMediaCenter.exe
2. 程序会在本机启动一个本地 Web 服务，并自动释放运行所需文件到用户目录。
3. 启动完成后，在浏览器访问程序启动时显示的本地地址即可使用（你的IP地址:58080）。

### 方式二：通过源码运行

1. 确保本机已安装 Java 21 及以上版本。
2. 在项目根目录执行 `mvnw.cmd spring-boot:run` 启动项目。
3. 启动完成后，在浏览器访问程序启动日志中显示的本地地址。

### 首次使用前建议确认

- **系统浏览器**：项目抓取能力依赖系统中的 Edge 或 Chrome，建议本机至少安装其中一个。
- **下载目录**：项目首次启动后会读取 `settings.json`，下载目录当前配置为 `D:\Project\AI-Project\1`，可在界面设置中修改。
- **运行数据目录**：程序会在用户本地目录下保存配置、下载历史和浏览器缓存，用于下次继续使用。

### 主要功能入口

- **分类浏览**：按分类抓取站点内容，查看封面、标题和分页结果。
- **视频解析**：输入视频页面地址，解析可播放或可下载的视频源。
- **系列视频**：在视频详情区域查看同系列条目，并支持一键加入系列下载。
- **下载中心**：统一查看下载队列、实时进度、暂停、恢复、取消和历史记录。
- **全局设置**：修改下载目录、清理浏览器缓存和调整本地运行状态。

### 说明

- **本项目定位**：这是一个本地运行的 Web 工具，不是云端服务。
- **配置兼容**：项目优先使用 `settings.json`，`config.json` 作为旧版兼容配置保留。
- **端口**：程序使用的本地端口以实际启动输出为准；如果端口被占用，需根据运行日志处理。 

## 2. 项目使用的技术

### 前端

- **原生 HTML / CSS / JavaScript**：前端代码位于 `src/main/resources/static/`，由 `index.html`、`style.css`、`app.js` 组成，负责分类浏览、视频解析、系列视频展示、下载中心、设置面板等界面与交互。
- **Hls.js**：在 `src/main/resources/static/index.html` 中通过 CDN 引入，用于前端播放和处理 `m3u8` 流媒体资源。
- **Google Fonts（Inter）**：在 `index.html` 中引入，用于页面字体展示，属于界面层资源。
- **前端框架：未使用**：当前项目没有使用 Vue、React、Angular 等前端框架，页面交互全部基于原生 JavaScript 实现。
- **UI 库：未使用**：当前项目没有引入 Element、Ant Design、Bootstrap、Vuetify 等 UI 组件库，界面样式为项目自定义实现。

### 后端

- **Java 21**：在 `pom.xml` 中声明为项目运行语言版本，是整个后端服务、下载调度和页面抓取逻辑的基础运行环境。
- **Spring Boot 3.2.4**：项目核心后端框架，负责应用启动、依赖注入、静态资源托管以及 Web API 能力。
- **Spring MVC / REST API**：通过 `spring-boot-starter-web` 提供接口能力，承担视频解析、分类抓取、下载任务、设置管理等 HTTP 接口。
- **SSE（Spring `SseEmitter`）**：在 `DownloadService` 中用于向前端实时推送下载进度、任务状态和历史记录变化。
- **Java 并发工具链**：项目在下载队列和浏览器访问控制中使用 `ExecutorService`、`BlockingQueue`、`ConcurrentHashMap`、`CopyOnWriteArrayList`、`ReentrantLock` 等并发能力，用于实现任务排队、状态管理和串行化抓取。

### 页面抓取与解析

- **Playwright for Java**：在 `pom.xml` 中引入 `com.microsoft.playwright:playwright`，由 `PlaywrightBrowserService` 负责驱动浏览器访问目标站点、处理 Cloudflare 验证、抓取页面内容和下载页资源。
- **系统浏览器通道（Edge / Chrome）**：`PlaywrightBrowserService` 中优先使用 `msedge`，失败后回退 `chrome`，用于在不内置完整浏览器内核的前提下完成站点抓取。
- **Jsoup**：在 `pom.xml` 中引入 `org.jsoup:jsoup`，用于从抓取后的 HTML 中提取标题、封面、系列列表、分类卡片和下载链接等结构化信息。
- **Java `HttpClient`**：在下载相关逻辑中用于直接请求资源地址和处理下载过程中的网络访问。

### 下载与文件处理

- **本地文件系统存储**：项目没有接入云存储或对象存储，下载文件直接保存到本地目录，下载路径由 `settings.json` / `config.json` 配置。
- **下载任务队列**：`DownloadService` 负责维护下载队列、活动任务、历史记录和批量加入下载逻辑。
- **分段下载与流媒体下载能力**：从 `SegmentedFileDownloaderTest`、`HlsDownloaderTest`、`DownloadService` 可以看出，项目同时覆盖普通文件下载与 HLS 资源下载场景。
- **本地 JSON 历史记录**：`download-history.json` 用于持久化下载历史，不依赖数据库。

### 配置与应用数据

- **JSON 配置文件**：`settings.json` 用于保存当前设置，`config.json` 用于兼容旧版配置格式。
- **应用数据目录管理**：项目通过 `AppPaths` 统一管理配置文件、下载历史、Playwright 数据目录等运行时文件位置，避免直接依赖源码目录。
- **Jackson**：随 Spring Boot Web 引入，实际用于配置文件读写、接口 JSON 序列化和下载历史持久化。

### 测试工具

- **JUnit 5**：通过 `spring-boot-starter-test` 使用，覆盖浏览抓取、视频解析、下载服务、配置管理、控制器等模块。
- **Spring Boot Test**：用于后端接口与 Spring 容器相关测试。
- **Mockito**：用于服务层和浏览器层的 mock 测试，验证抓取、解析、下载调度等逻辑分支。
- **Maven Surefire Plugin**：在 `pom.xml` 中配置，用于执行单元测试与集成测试。

### 构建与工程化

- **Maven**：项目主构建工具，负责依赖管理、测试执行和打包。
- **Maven Wrapper**：仓库中的 `mvnw`、`mvnw.cmd`、`.mvn/` 用于在不同机器上统一 Maven 构建环境。
- **Spring Boot Maven Plugin**：用于生成可运行的 Spring Boot fat jar。
- **Lombok**：在 `pom.xml` 中声明为可选依赖，用于减少样板代码；当前代码中是否大量使用，待确认。
- **Windows 单文件 EXE 分发产物**：项目当前支持打包为单文件 Windows 可执行程序，用于脱离源码目录直接运行；具体打包链路在当前仓库快照中为项目内定制流程。

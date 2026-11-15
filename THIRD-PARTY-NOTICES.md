# 第三方组件与许可证声明

本文件记录 BearCoding 实际使用或随包分发的第三方组件、版本、许可证类型和上游地址，供源码发布和 Windows 便携包分发时保留必要的版权与许可声明。

- 本文件只写**可以从本仓库构件核实**的事实。凡本地构件没有声明许可证的，标为「待上游核实」，不凭经验填写。
- BearCoding 自研源码的许可证见 [LICENSE](LICENSE)（MIT）。第三方组件仍归各自版权所有者，其许可证不因本项目采用 MIT 而改变。
- 图片与图标资源的授权状态单独记录在 [ASSETS.md](ASSETS.md)。

核对依据：

- Maven 依赖树：`mvn -o dependency:list -DincludeScope=runtime`（2026-11-15，本地 `~/.m2` 缓存）；
- 各构件 `*.pom` 的 `<licenses>` 段，以及 jar 内 `META-INF/LICENSE`；
- npm：`frontend/node_modules/*/package.json` 与 `desktop/node_modules/*/package.json` 的 `license` 字段；
- 打包产物：`desktop/release/win-unpacked/`、`desktop/.build/jre/`。

---

## 1. 后端（Java / Maven）

工程坐标 `com.example:bailianAI:3.0.0`，JDK 17，Spring Boot 3.5.0，Spring AI 1.0.0。

### 1.1 直接依赖

| 组件 | 版本 | 许可证 | 上游 | 声明来源 |
|---|---|---|---|---|
| Spring Boot（starter-web / starter-test / parent BOM） | 3.5.0 | Apache License 2.0 | https://github.com/spring-projects/spring-boot | POM `<licenses>` |
| Spring Framework（spring-core / web / webmvc / context 等） | 6.2.7 | Apache License 2.0 | https://github.com/spring-projects/spring-framework | POM `<licenses>` |
| Spring AI（spring-ai-starter-model-openai、spring-ai-model 等） | 1.0.0 | Apache License 2.0 | https://github.com/spring-projects/spring-ai | POM `<licenses>` |
| MyBatis（mybatis） | 3.5.14 | Apache License 2.0 | https://github.com/mybatis/mybatis-3 | jar `META-INF/LICENSE` + `NOTICE` |
| MyBatis Spring Boot Starter / mybatis-spring | 3.0.3 | Apache License 2.0 | https://github.com/mybatis/spring-boot-starter | 父 POM `mybatis-parent` 声明 |
| HikariCP | 6.3.0 | Apache License 2.0 | https://github.com/brettwooldridge/HikariCP | POM `<licenses>` |
| sqlite-jdbc（Xerial） | 3.47.1.0 | Apache License 2.0 | https://github.com/xerial/sqlite-jdbc | POM `<licenses>` |
| JNA / jna-platform | 5.15.0 | LGPL-2.1-or-later **或** Apache-2.0（双许可） | https://github.com/java-native-access/jna | POM `<licenses>`（两项都列出） |
| MCP Java SDK（mcp-core、mcp-json-jackson2） | 1.1.0 | MIT | https://github.com/modelcontextprotocol/java-sdk | POM `<licenses>` |
| Jackson（databind / core / annotations / datatype / dataformat-yaml / module-jsonSchema） | 2.19.0 | Apache License 2.0 | https://github.com/FasterXML/jackson | POM `<licenses>` |
| SnakeYAML | 2.4 | Apache License 2.0 | https://bitbucket.org/snakeyaml/snakeyaml | POM `<licenses>` |
| Apache Tomcat（tomcat-embed-core / el / websocket） | 10.1.41 | Apache License 2.0 | https://tomcat.apache.org/ | POM `<licenses>` |
| SLF4J API | 2.0.17 | MIT | https://www.slf4j.org/ | jar `META-INF/LICENSE.txt`（QOS.ch Sarl） |
| Logback（logback-classic / logback-core） | 1.5.18 | EPL-1.0 或 LGPL-2.1（双许可，二选一） | https://logback.qos.ch/ | 父 POM `logback-parent` 声明 |
| Micrometer（micrometer-core / observation / commons） | 1.15.0 | Apache License 2.0 | https://github.com/micrometer-metrics/micrometer | POM `<licenses>` |
| Reactor Core | 3.7.6 | Apache License 2.0 | https://github.com/reactor/reactor-core | POM `<licenses>` |
| networknt json-schema-validator | 2.0.0 | Apache License 2.0 | https://github.com/networknt/json-schema-validator | POM `<licenses>` |
| jtokkit | 1.1.0 | MIT | https://github.com/knuddelsgmbh/jtokkit | POM `<licenses>` |
| Jetty（jetty-server、jetty-ee10-servlet）**仅 test scope** | 12.0.16 | EPL-2.0 或 Apache-2.0（双许可） | https://github.com/jetty/jetty.project | 父 POM `jetty-project` 声明；不进入发行包 |
| JUnit Jupiter / JUnit Platform **仅 test scope** | 5.12.2 / 1.12.2 | Eclipse Public License 2.0 | https://junit.org/ | 本地 POM 声明；不进入发行包 |

### 1.2 随 JAR 一起分发的传递依赖（86 项，已逐项核对）

核对方法：对 `mvn -o dependency:list -DincludeScope=runtime` 解析出的每个构件，读取本机 `~/.m2` 中对应 `*.pom` 的 `<licenses>`；若该 POM 没有声明，则沿 `<parent>` 向上最多 5 层取父 POM 的声明（构件自身的 POM 常常只写 `<parent>`，许可证在父 POM 里）。**86 项全部解析成功，没有未确认项。**

| 许可证 | 数量 | 构件 |
|---|---|---|
| Apache License 2.0 | 70 | classmate 1.7.0 · context-propagation 1.1.3 · HikariCP 6.3.0 · itu 1.14.0 · jackson-annotations 2.19.0 · jackson-core 2.19.0 · jackson-databind 2.19.0 · jackson-dataformat-yaml 2.19.0 · jackson-datatype-jdk8 2.19.0 · jackson-datatype-jsr310 2.19.0 · jackson-module-jsonSchema 2.19.0 · jackson-module-parameter-names 2.19.0 · jsonschema-generator 4.37.0 · jsonschema-module-jackson 4.37.0 · jsonschema-module-swagger-2 4.37.0 · json-schema-validator 2.0.0 · log4j-api 2.24.3 · log4j-to-slf4j 2.24.3 · micrometer-commons 1.15.0 · micrometer-core 1.15.0 · micrometer-observation 1.15.0 · mybatis 3.5.14 · mybatis-spring 3.0.3 · mybatis-spring-boot-autoconfigure 3.0.3 · mybatis-spring-boot-starter 3.0.3 · reactor-core 3.7.6 · snakeyaml 2.4 · spring-ai-autoconfigure-model-chat-client 1.0.0 · spring-ai-autoconfigure-model-chat-memory 1.0.0 · spring-ai-autoconfigure-model-chat-observation 1.0.0 · spring-ai-autoconfigure-model-embedding-observation 1.0.0 · spring-ai-autoconfigure-model-image-observation 1.0.0 · spring-ai-autoconfigure-model-openai 1.0.0 · spring-ai-autoconfigure-model-tool 1.0.0 · spring-ai-autoconfigure-retry 1.0.0 · spring-ai-client-chat 1.0.0 · spring-ai-commons 1.0.0 · spring-ai-model 1.0.0 · spring-ai-openai 1.0.0 · spring-ai-retry 1.0.0 · spring-ai-starter-model-openai 1.0.0 · spring-ai-template-st 1.0.0 · spring-aop 6.2.7 · spring-beans 6.2.7 · spring-context 6.2.7 · spring-context-support 6.2.7 · spring-core 6.2.7 · spring-expression 6.2.7 · spring-jcl 6.2.7 · spring-jdbc 6.2.7 · spring-messaging 6.2.7 · spring-tx 6.2.7 · spring-web 6.2.7 · spring-webflux 6.2.7 · spring-webmvc 6.2.7 · spring-boot 3.5.0 · spring-boot-autoconfigure 3.5.0 · spring-boot-starter 3.5.0 · spring-boot-starter-jdbc 3.5.0 · spring-boot-starter-json 3.5.0 · spring-boot-starter-logging 3.5.0 · spring-boot-starter-tomcat 3.5.0 · spring-boot-starter-web 3.5.0 · spring-retry 2.0.12 · sqlite-jdbc 3.47.1.0 · swagger-annotations 2.2.25 · tomcat-embed-core 10.1.41 · tomcat-embed-el 10.1.41 · tomcat-embed-websocket 10.1.41 · validation-api 1.1.0.Final |
| MIT | 5 | jtokkit 1.1.0 · jul-to-slf4j 2.0.17 · mcp-core 1.1.0 · mcp-json-jackson2 1.1.0 · slf4j-api 2.0.17 |
| LGPL-2.1-or-later **或** Apache-2.0（双许可） | 2 | jna 5.15.0 · jna-platform 5.15.0 |
| EPL-1.0 **或** LGPL-2.1（双许可） | 2 | logback-classic 1.5.18 · logback-core 1.5.18 |
| BSD-3-Clause（POM 写作 `BSD-3-Clause` / `The BSD License` / `BSD licence`） | 3 | antlr4-runtime 4.13.1 · ST4 4.3.4 · antlr-runtime 3.5.3 |
| CC0 / Public Domain（HdrHistogram 同时标注 BSD-2-Clause） | 2 | HdrHistogram 2.2.2 · LatencyUtils 2.0.3 |
| EPL-2.0 **或** GPL2 with CPE（双许可） | 1 | jakarta.annotation-api 2.1.1 |
| MIT-0 | 1 | reactive-streams 1.0.4 |

说明：

- 双许可项按「任选其一」处理；本项目对 logback 选择 EPL-1.0 路径（Spring Boot 默认依赖，未修改其源码），对 JNA 选择 Apache-2.0 路径。
- 本表只声明**许可证类型**。Apache-2.0 / BSD / MIT 的实际义务是「保留版权与许可声明、附带 NOTICE（若有）」；fat JAR 内部已经保留了各构件的 `META-INF/LICENSE` 与 `META-INF/NOTICE`（例如 mybatis 的 `NOTICE` 提到 iBATIS 与 ASF），本项目没有剥离它们。
- test scope 的 37 项（JUnit 5.12.2 / Platform 1.12.2 = EPL-2.0、Mockito 5.17.0 = MIT、AssertJ 3.27.3 = Apache-2.0、Hamcrest 3.0 = BSD-3-Clause、Jetty 12.0.16/12.0.21 = EPL-2.0 或 Apache-2.0、jakarta.servlet-api 6.0.0 = EPL-2.0 或 GPL2+CPE 等）**不进入发行包**，仅供构建与测试使用。

### 1.3 Java Runtime（jlink 生成，随便携包分发）

| 项 | 实际值 |
|---|---|
| 发行版 | Oracle JDK（Oracle 官方构建） |
| 版本 | 17.0.12+8-LTS-286（`java version "17.0.12" 2024-07-16 LTS`） |
| 构建用 JDK | 本机 `JAVA_HOME=D:\jdk17`，同为 17.0.12+8-LTS-286 |
| 运行时许可证 | Oracle No-Fee Terms and Conditions（NFTC），见 `desktop/.build/jre/legal/java.base/LICENSE` |
| jlink 模块集 | `java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.instrument,java.logging,java.management,java.security.sasl,java.naming,java.net.http,java.rmi,java.scripting,java.security.jgss,java.transaction.xa,java.sql,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.unsupported` |
| legal 文件 | 随 Runtime 保留：`legal/<module>/{LICENSE,COPYRIGHT}` 与 `legal/java.base/*.md`（aes、asm、cldr、icu、unicode、zlib、wepoll 等） |
| 打包后位置 | `win-unpacked/resources/jre/legal/...`（已验证存在） |

Oracle 官方 FAQ（[Oracle Java SE Licensing FAQ](https://www.oracle.com/java/technologies/javase/jdk-faqs.html)，更新于 2025-09-16）明确：

- Java 17 的 **17.0.12 及更早**版本对全部用户适用 NFTC；**17.0.13 及之后**改用 My Oracle Support / OTN 许可。
- NFTC「intended to permit use of the Program, including runtime images produced by the jlink tool, by any user, including in commercial and production use」。
- 「Redistribution is permitted as long as it is not for a fee.」

因此：**当前 17.0.12 jlink 运行时以免费、不收费的方式随 BearCoding 便携包再分发，在 NFTC 条款下是被允许的**，前提是不对分发收费、不移除 Oracle 的权属声明、不反编译。若将来对该 EXE 收费（含付费下载、付费捆绑），该结论立即失效，必须改用其他运行时或另行取得 Oracle 授权。

**安全维护状态**：17.0.12 发布于 2024-07，是 Java 17 最后一个 NFTC 免费版本。2024-10 起 Oracle 对 17.0.13+ 的更新只通过 My Oracle Support / OTN 提供，NFTC 渠道不再提供 Java 17 的安全补丁。也就是说，随包分发的运行时**缺少 2024-10 之后的所有 Oracle 关键补丁更新（CPU）**。这是 BINARY 发布的独立决策项，见根 README 与 [desktop/README.md](desktop/README.md) 的已知限制。本轮未更换 JDK、未覆盖已有 EXE。

---

## 2. 前端（npm）

版本取自 `frontend/node_modules/*/package.json`（与 `package-lock.json` 锁定一致）。

| 组件 | 实际版本 | 许可证 | 上游 |
|---|---|---|---|
| Vue | 3.5.33 | MIT | https://github.com/vuejs/core |
| Vue Router | 4.6.4 | MIT | https://github.com/vuejs/router |
| Naive UI | 2.43.2 | MIT | https://github.com/tusen-ai/naive-ui |
| axios | 1.16.0 | MIT | https://github.com/axios/axios |
| marked | 18.0.13 | MIT | https://github.com/markedjs/marked |
| DOMPurify | 3.4.1 | MPL-2.0 OR Apache-2.0（双许可，取 Apache-2.0） | https://github.com/cure53/DOMPurify |
| uuid | 14.0.0 | MIT | https://github.com/uuidjs/uuid |
| core-js | 3.49.0 | MIT | https://github.com/zloirock/core-js |
| vfonts（字体集合） | 0.0.3 | MIT（字体本身另有 OFL，见第 4 节） | https://github.com/07akioni/vfonts |
| @vicons/fluent | 0.13.0 | MIT | https://github.com/07akioni/vicons |
| @vicons/ionicons5 | 0.13.0 | MIT | https://github.com/07akioni/vicons |
| @vue/cli-service（dev） | 5.0.9 | MIT | https://github.com/vuejs/vue-cli |
| eslint（dev） | 7.32.0 | MIT | https://github.com/eslint/eslint |
| eslint-plugin-vue（dev） | 8.7.1 | MIT | https://github.com/vuejs/eslint-plugin-vue |
| Playwright（dev） | 1.64.0 | Apache-2.0 | https://github.com/microsoft/playwright |

上表是 `frontend/package.json` 直接声明的 **11 个运行时依赖**。把它们的 `dependencies` 递归展开后，实际进入 `frontend/dist` 的生产依赖闭包是 **76 个包**，全部有 `package.json` 里明确声明的许可证：

| 许可证 | 数量 |
|---|---|
| MIT | 70 |
| BSD-3-Clause | 2（`highlight.js` 10.7.3、`source-map-js` 1.2.1） |
| BSD-2-Clause | 1（`entities` 7.0.1） |
| ISC | 1（`picocolors` 1.1.1） |
| Apache-2.0 | 1（`@juggle/resize-observer` 3.4.0） |
| MPL-2.0 OR Apache-2.0（双许可，本项目取 Apache-2.0） | 1（`dompurify` 3.4.1） |

**没有 `UNKNOWN` 或缺失许可证的包。** 完整逐包清单可用 `npm ls --omit=dev --all` 在安装后重新生成。

`devDependencies`（`@vue/cli-service` 及其 webpack/babel 工具链、eslint、playwright）只用于构建与测试，**不随发布包分发**，其许可证不在本文件逐项列出。

---

## 3. 桌面外壳（Electron）

| 组件 | 版本 | 许可证 | 上游 | 打包后声明 |
|---|---|---|---|---|
| Electron | 45.3.2 | MIT | https://github.com/electron/electron | `win-unpacked/LICENSE.electron.txt`（Copyright (c) Electron contributors / 2013-2020 GitHub Inc.） |
| Chromium 及其第三方组件 | 随 Electron 45.3.2 | BSD-3-Clause 及 Chromium 内含的多个第三方许可 | https://www.chromium.org/ | `win-unpacked/LICENSES.chromium.html`（约 5.7 MB 完整许可汇编） |
| electron-builder | 26.18.0 | MIT | https://github.com/electron-userland/electron-builder | 构建工具，不进入产物 |
| Node.js（构建期） | 22.20.0 | MIT | https://nodejs.org/ | 不进入产物 |

已核实：portable EXE 的解包目录 `desktop/release/win-unpacked/` 同时存在 `LICENSE.electron.txt` 与 `LICENSES.chromium.html`，且 `resources/jre/legal/` 随包保留。electron-builder 的 `portable` 目标打包的就是该目录，因此这两份声明**随 EXE 一起分发**。

`default_app.asar`（electron-builder 附带）不在本项目的许可义务范围内，但它随包存在，不影响上述声明。

**仍未做的**：没有把 `THIRD-PARTY-NOTICES.md` 与 `LICENSE` 打进 EXE。当前 EXE 内只有 Electron / Chromium / Oracle JDK 三方的声明，**没有 BearCoding 自己的 MIT 许可证文本，也没有前端依赖和字体的声明**。正式分发前应把这两份文件加进 `extraResources`，见第 6 节。

---

## 4. 字体（随构建产物分发）

`frontend/src/main.js` 引入了两个字体样式表：

```js
import 'vfonts/Lato.css'
import 'vfonts/FiraCode.css'
```

构建后实际进入 `backend/src/main/resources/static/fonts/`，并随 JAR / EXE 分发：

| 字体文件 | 字体 | 许可证 | 版权声明 |
|---|---|---|---|
| `LatoLatin-Regular.woff2` | Lato | SIL Open Font License 1.1 | Copyright (c) 2010, Łukasz Dziedzic (dziedzic@typoland.com), with Reserved Font Name Lato. |
| `LatoLatin-Semibold.woff2` | Lato | SIL Open Font License 1.1 | 同上 |
| `FiraCode-Regular.woff2` | Fira Code | SIL Open Font License 1.1 | Copyright (c) 2014, The Fira Code Project Authors (https://github.com/tonsky/FiraCode) |

OFL 1.1 第 2 条要求「each copy contains the above copyright notice and this license」。因此下列 OFL 全文与版权行必须随字体一起分发（本文件即为其载体；正式 EXE 需要把本文件打进包内）。

> 说明：`vfonts` 包内 `font-license/Lato-LISCENSE.md` 只有一行指向 OFL 的链接，没有正文；上面的 Lato 版权行取自 Lato 上游随字体发布的 `OFL.txt`。Fira Code 的版权行与全文取自 `vfonts/font-license/FiraCode-LISCENSE.txt`。

### SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007

```text
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

---

## 5. 未核实清单

以下项目本轮**没有**取得可引用的确认，不应被视为已满足许可要求：

1. `frontend/src/assets/` 下的图片、`desktop/build/icon.png`、`frontend/public/favicon.ico` 的**来源与授权**未确认，见 [ASSETS.md](ASSETS.md)。
2. `ChatView.vue` 中硬编码的第三方头像 URL 未取得授权，见 [ASSETS.md](ASSETS.md)。
3. 未对便携 EXE 做二进制成分扫描（`win-unpacked` 内的 DLL 属于 Electron/Chromium 官方发行版，未逐个核对）。
4. 本文件记录的是各构件 POM 声明的许可证类型，未逐份比对完整许可证正文；Apache-2.0 / BSD / MIT / OFL 的义务已按「保留声明、不剥离 NOTICE、随包附带本文件与 LICENSE」的方式满足。

---

## 6. 建议的最小后续动作

1. 把 `LICENSE` 与 `THIRD-PARTY-NOTICES.md` 加入 `desktop/package.json` 的 `extraResources`，让 EXE 内同时存在 BearCoding、前端依赖、字体和 Oracle JDK 的声明。（**二进制发布前必须做**）
2. 解决 [ASSETS.md](ASSETS.md) 里列出的图片授权问题。
3. 决定是否把 Oracle JDK 17.0.12 换成仍在安全维护期内的 OpenJDK 17 发行版（Temurin / Microsoft Build of OpenJDK / Zulu 等，GPLv2+CPE）。

第 1.2 节的传递依赖清单已在 2026-11-15 通过父 POM 回溯补全，不再有待核实项。

package com.aiguanli.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.console.enabled", havingValue = "true", matchIfMissing = true)
public class ConsoleChat implements CommandLineRunner {

    private final AssistantService assistant;
    private final DiscussionService discussion;
    private final DecompositionService decomposition;
    private final ProgressService progress;
    private final CollaborationService collaboration;
    private final DecisionReviewService decisions;
    private final StorageSettings storage;
    private final ObjectMapper mapper;
    private final ObjectProvider<KnowledgeService> knowledge;
    private final ConfigurableApplicationContext context;
    private final PublicKnowledgeLoader publicLoader = new PublicKnowledgeLoader();

    public ConsoleChat(AssistantService assistant, DiscussionService discussion,
            DecompositionService decomposition, ProgressService progress, CollaborationService collaboration,
            DecisionReviewService decisions, StorageSettings storage, ObjectMapper mapper,
            ObjectProvider<KnowledgeService> knowledge, ConfigurableApplicationContext context) {
        this.assistant = assistant;
        this.discussion = discussion;
        this.decomposition = decomposition;
        this.progress = progress;
        this.collaboration = collaboration;
        this.decisions = decisions;
        this.storage = storage;
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        String conversationId = UUID.randomUUID().toString();
        try {
            // 一次提问模式方便 IDE、脚本和实验演示使用。
            for (String arg : args) {
                if (arg.startsWith("--question=")) {
                    System.out.println(assistant.ask(arg.substring("--question=".length())));
                    return;
                }
            }
            System.out.println("爱管理 AI 助手：输入问题开始对话，输入 /help 查看命令，/exit 退出。");
            System.out.println("已开启近期对话记忆（20条消息窗口，包含提问和回答，退出后清空）。");
            System.out.println("支持 /analyze examples/discussion.json 解析模拟群聊；尚未接入项目数据库。");
            System.out.println(storage.localPersistenceEnabled()
                    ? "存储模式：本地文件。远程存储尚未接入。"
                    : "存储模式：不保存本地草稿或索引。远程存储尚未接入，结果尚未持久化，内存知识库退出后清空。");
            System.out.println("支持 /decompose 需求描述 生成需求拆解草稿；RAG 启用时检索公共方法和非模拟的已审核项目决策。");
            System.out.println("支持 /progress examples/tasks.json 调用工具分析模拟任务进度。");
            System.out.println("支持 /collaborate examples/collaboration.json 运行纪要 → 风险 → 协调；RAG 启用时可参考已审核决策。");
            System.out.println("支持 /extract-decisions 文件路径 提取群聊候选决策，/decisions 查看，/review-decision 编号 人工审核后入库（需 RAG）。");
            System.out.println(knowledge.getIfAvailable() == null
                    ? "RAG 未启用：需要时用 run.ps1 -Rag 启动。"
                    : "RAG 已启用：/import 文件路径、/ask 问题 查询项目文档；/sources、/import-public 编号、/ask-public 问题 使用公共知识。");
            var scanner = new Scanner(System.in, StandardCharsets.UTF_8);
            while (true) {
                System.out.print("\n你：");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String question = scanner.nextLine().strip();
                if (question.equalsIgnoreCase("/exit")) {
                    break;
                }
                if (question.isBlank()) {
                    continue;
                }
                if (question.equalsIgnoreCase("/help")) {
                    System.out.println("/new 清空本次会话记忆；/analyze 文件路径 解析 UTF-8 JSON 聊天记录；/exit 退出。");
                    System.out.println("/decompose 需求描述 生成用户故事、验收标准、开发任务及待确认问题；RAG 可用时检索公共方法资料。");
                    System.out.println("/progress 文件路径 通过只读工具计算模拟任务进度，生成待审核分析草稿。");
                    System.out.println("/collaborate 文件路径 用模拟聊天和任务快照运行三角色交接；只显示结果，不保存或修改任务。");
                    System.out.println("决策入库：/extract-decisions 文件路径 提取候选；/decisions 列表；/decision 编号 核对原文；/review-decision 编号 人工审核；/publish-decision 编号 重试入库。需 -Rag，记录仅在本次运行内存。");
                    System.out.println("RAG：/import 文件路径 导入项目文档；/ask 问题 只查项目文档；/docs 列出已导入资料。");
                    System.out.println("公共知识：/sources 查看权威来源；/import-public 编号（或 all）联网读取并向量化；/ask-public 问题 只查公共资料。导入与问答需 run.ps1 -Rag。");
                    continue;
                }
                if (question.equalsIgnoreCase("/new")) {
                    assistant.clear(conversationId);
                    System.out.println("已清空本次会话记忆。");
                    continue;
                }
                try {
                    if (question.equals("/decisions") || isCommand(question, "/extract-decisions")
                            || isCommand(question, "/decision") || isCommand(question, "/review-decision")
                            || isCommand(question, "/publish-decision")) {
                        handleDecision(question, scanner);
                    } else if (isCommand(question, "/import") || isCommand(question, "/ask") || question.equals("/docs")
                            || question.equals("/sources") || isCommand(question, "/import-public") || isCommand(question, "/ask-public")) {
                        handleKnowledge(question);
                    } else if (isCommand(question, "/collaborate")) {
                        String fileName = fileArgument(question, "/collaborate");
                        if (fileName.isBlank()) {
                            System.out.println("用法：/collaborate examples/collaboration.json");
                            continue;
                        }
                        System.out.println("正在运行固定协作：Java 统计 → 纪要整理 → 风险评估 → 协调汇总；将依次调用三个模型角色，请稍候……");
                        var result = collaboration.collaborate(Path.of(fileName), knowledge.getIfAvailable());
                        System.out.println("\n" + result.preview());
                        System.out.println("\n协作结果仅显示，未保存到本地或远程数据库。没有执行任务变更。");
                    } else if (isCommand(question, "/progress")) {
                        String fileName = fileArgument(question, "/progress");
                        if (fileName.isBlank()) {
                            System.out.println("用法：/progress examples/tasks.json");
                            continue;
                        }
                        System.out.println("正在通过工具查询模拟快照并生成分析，请稍候……");
                        var result = progress.analyze(Path.of(fileName));
                        var report = result.report();
                        System.out.println("\n工具调用成功：getSprintProgress，调用次数：" + result.toolCalls());
                        System.out.println("数据来源：模拟文件 " + report.source() + "；快照日期：" + report.snapshotDate());
                        System.out.println("Java 统计：已完成 " + report.completedTasks() + "/" + report.totalTasks()
                                + "；任务数量完成率：" + (report.completionPercent() == null ? "无法计算" : report.completionPercent() + "%"));
                        System.out.println("延期任务：" + report.overdueTaskIds() + "；阻塞任务：" + report.blockedTaskIds());
                        System.out.println("\nAI 分析草稿（模拟数据，待人工核对，不代表真实项目进度）：\n" + result.answer());
                        printPersistence("进度草稿", result.file());
                    } else if (isCommand(question, "/decompose")) {
                        String requirement = question.substring("/decompose".length()).strip();
                        if (requirement.isBlank()) {
                            System.out.println("用法：/decompose 项目负责人创建任务并分配给成员。");
                            continue;
                        }
                        var knowledgeService = knowledge.getIfAvailable();
                        System.out.println(knowledgeService == null ? "正在根据输入需求生成草稿（RAG 未启用），请稍候……"
                                : "正在检索公共方法及非模拟的已审核项目决策，生成需求拆解草稿，请稍候……");
                        var draft = knowledgeService == null ? decomposition.decompose(requirement)
                                : decomposition.decomposeWithKnowledge(requirement, knowledgeService);
                        System.out.println("\n" + draft.preview());
                        printPersistence("需求草稿", draft.file());
                    } else if (isCommand(question, "/analyze")) {
                        String fileName = fileArgument(question, "/analyze");
                        if (fileName.isBlank()) {
                            System.out.println("用法：/analyze examples/discussion.json");
                            continue;
                        }
                        List<DiscussionMessage> messages = mapper.readValue(
                                Files.readString(Path.of(fileName), StandardCharsets.UTF_8), new TypeReference<>() {});
                        var analysis = discussion.analyze(messages);
                        System.out.println("\n纪要草稿（需人工核对；仅解析该文件，不写入聊天记忆或项目数据库）：");
                        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(analysis));
                    } else if (question.startsWith("/")) {
                        System.out.println("未知命令，请输入 /help。");
                    } else {
                        System.out.println("\n助手：" + assistant.ask(conversationId, question));
                    }
                } catch (CollaborationService.CollaborationFailedException ex) {
                    System.err.println("协作阶段 " + ex.stage() + " 失败，后续阶段未执行，未生成完整协作草稿。");
                    ex.trace().forEach(stage -> System.err.println(stage.id() + "：" + stage.status()));
                    if (ex.getCause() instanceof CheckedOutputConverter.InvalidOutputException invalid) {
                        System.err.println(invalid.getMessage());
                    }
                    System.err.println("请核对输入、模型格式、来源编号和连接状态后重试。结果未保存，任务未修改。");
                } catch (ProgressService.ToolNotCalledException ex) {
                    System.err.println("模型未调用 getSprintProgress，未完成工具分析。请重试并检查所用模型是否支持工具调用。");
                } catch (IOException ex) {
                    System.err.println("文件读取、保存或 JSON 解析失败，请检查路径、文件权限和格式。");
                } catch (IllegalArgumentException ex) {
                    System.err.println("输入或输出格式不符合要求，请检查输入材料和模型返回格式。");
                } catch (IllegalStateException ex) {
                    System.err.println("模型回答为空，或结构及来源编号不符合要求。请重试并核对原文。");
                } catch (RuntimeException ex) {
                    // 不回显供应商响应体，避免把请求内容或凭证输出到终端。
                    System.err.println("调用失败：请检查网络、服务地址、模型名称、API Key 和账户额度。");
                    System.err.println("错误类型：" + ex.getClass().getSimpleName());
                }
            }
        } finally {
            assistant.clear(conversationId);
            context.close();
        }
    }

    private void handleKnowledge(String command) throws IOException {
        if (command.equals("/sources")) {
            System.out.println("公共知识来源目录（列出不代表已导入；正文和向量默认仅保存在运行内存）：");
            for (var source : PublicKnowledgeCatalog.sources()) {
                System.out.println(source.id() + "：" + source.title() + " | " + source.publisher() + " | " + source.version());
                System.out.println("  主题：" + source.topics() + "\n  原文：" + source.url());
            }
            System.out.println("导入示例：/import-public agile；全部导入：/import-public all。会联网并调用向量 API。");
            return;
        }
        KnowledgeService service = knowledge.getIfAvailable();
        if (service == null) {
            System.out.println("请先 /exit 退出，再在 PowerShell 运行 .\\run.ps1 -Rag 并配置向量模型密钥。");
            return;
        }
        if (command.equals("/docs")) {
            var files = service.listSources();
            if (files.isEmpty()) {
                System.out.println("知识库为空，请用 /import 文件路径 或 /import-public 编号 导入。");
            } else {
                files.forEach(file -> {
                    System.out.println("[" + (file.scope().equals("PUBLIC") ? "公共知识" : "项目文档") + "] "
                            + file.name() + "：" + file.chunkIds().size() + " 个片段");
                    if (file.url() != null) {
                        System.out.println("  来源：" + file.url() + "\n  版本：" + file.version()
                                + "；抓取时间（UTC）：" + file.fetchedAt() + "\n  署名/许可：" + file.licenseNotice());
                    }
                    if (file.decision() != null) { printDecisionProvenance(file.decision()); }
                });
            }
        } else if (isCommand(command, "/import-public")) {
            String id = command.substring("/import-public".length()).strip();
            if (id.isBlank()) {
                System.out.println("用法：/import-public agile；输入 /sources 查看编号。");
                return;
            }
            List<PublicKnowledgeCatalog.Source> selected = id.equals("all")
                    ? PublicKnowledgeCatalog.sources() : List.of(PublicKnowledgeCatalog.find(id));
            int success = 0;
            for (var source : selected) {
                System.out.println("正在联网读取 " + source.title() + "，提取正文并调用向量模型……");
                try {
                    var result = service.importPublic(publicLoader.load(source));
                    success++;
                    System.out.println((result.unchanged() ? "正文未变，跳过重复向量化："
                            : storage.localPersistenceEnabled() ? "已导入并保存：" : "已导入运行内存：")
                            + result.name() + "，" + result.chunks() + " 个片段。\n原文：" + source.url());
                } catch (IOException ex) {
                    System.err.println(source.id() + " 导入失败（网络、解析或存储问题），该来源原有索引保持不变。");
                    System.err.println("请检查原文是否能访问：" + source.url());
                }
            }
            System.out.println("本次成功/未变：" + success + "/" + selected.size() + "。可输入 /docs 核对。");
            if (!storage.localPersistenceEnabled()) {
                System.out.println("未写入本地文件或远程数据库；退出后需重新导入。");
            }
        } else if (isCommand(command, "/import")) {
            String file = fileArgument(command, "/import");
            if (file.isBlank()) {
                System.out.println("用法：/import examples/knowledge.md");
                return;
            }
            System.out.println("正在读取文档并调用向量模型，请稍候……");
            var result = service.importFile(Path.of(file));
            System.out.println((result.unchanged() ? "文件未变，跳过重复导入："
                    : storage.localPersistenceEnabled() ? "已导入并保存：" : "已导入本次运行内存，未写入本地文件或远程存储：")
                    + result.name() + "，" + result.chunks() + " 个片段。");
        } else {
            boolean publicQuestion = isCommand(command, "/ask-public");
            String question = command.substring((publicQuestion ? "/ask-public" : "/ask").length()).strip();
            if (question.isBlank()) {
                System.out.println(publicQuestion ? "用法：/ask-public Sprint Review 和 Retrospective 有什么区别？"
                        : "用法：/ask AI 用户故事拆解计划在哪个 Sprint？");
                return;
            }
            var answer = publicQuestion ? service.askPublic(question) : service.ask(question);
            System.out.println("\n文档助手：" + answer.answer());
            if (!answer.sources().isEmpty()) {
                System.out.println("\n检索参考片段（请核对回答是否得到原文支持）：");
                for (var source : answer.sources()) {
                    System.out.println("[" + source.number() + "] " + source.source() + "，片段 " + source.chunk());
                    if (source.url() != null) {
                        System.out.println("原文：" + source.url() + "\n发布方：" + source.publisher()
                                + "；版本：" + source.version() + "；抓取时间（UTC）：" + source.fetchedAt()
                                + "\n署名/许可：" + source.licenseNotice());
                    }
                    if (source.decision() != null) { printDecisionProvenance(source.decision()); }
                    System.out.println(source.text());
                }
            }
        }
    }

    private void handleDecision(String command, Scanner scanner) throws IOException {
        if (command.equals("/decisions")) {
            if (decisions.list().isEmpty()) {
                System.out.println("本次运行还没有候选决策。先用 -Rag 启动，再 /extract-decisions examples/decision-discussion.json。");
            }
            decisions.list().forEach(item -> System.out.println(item.id() + " | " + decisionStatus(item.status())
                    + " | " + item.originalText()));
            return;
        }
        if (isCommand(command, "/decision")) {
            String id = fileArgument(command, "/decision");
            if (id.isBlank()) { System.out.println("用法：/decision 决策编号"); return; }
            printDecision(decisions.get(id));
            return;
        }
        KnowledgeService service = knowledge.getIfAvailable();
        if (service == null) {
            System.out.println("此流程需要 RAG。请 /exit 后运行 .\\run.ps1 -Rag，再提取决策；退出会清空候选和审核记录。");
            return;
        }
        if (isCommand(command, "/extract-decisions")) {
            String file = fileArgument(command, "/extract-decisions");
            if (file.isBlank()) { System.out.println("用法：/extract-decisions examples/decision-discussion.json"); return; }
            System.out.println("正在提取明确表达的决策候选；尚未审核，暂不加入知识库……");
            var result = decisions.extract(Path.of(file));
            System.out.println(result.reused() ? "输入未变，返回已有候选及当前审核状态。" : "已提取候选决策。建议、待办和待确认问题不自动作为决策入库。");
            for (var item : result.decisions()) { printDecision(item); }
            if (result.decisions().isEmpty()) { System.out.println("未提取到明确决策，没有创建待审核条目。"); }
            System.out.println("核对原文后输入 /review-decision 编号。当前所有记录只在运行内存，退出即清空。");
        } else if (isCommand(command, "/review-decision")) {
            String id = fileArgument(command, "/review-decision");
            if (id.isBlank()) { System.out.println("用法：/review-decision 决策编号"); return; }
            var current = decisions.get(id);
            printDecision(current);
            if (current.status() != DecisionReviewService.Status.PENDING_REVIEW) {
                System.out.println("该条已经审核，不能覆盖原审核记录。采纳后尚未入库的条目可用 /publish-decision 编号 重试。");
                return;
            }
            String choice = readReviewLine(scanner, "处理结论：1=直接采纳原稿（不改文字），2=编辑文字后采纳，3=否决，0=取消：");
            if (choice == null || choice.equals("0")) { System.out.println("已取消，仍待审核。"); return; }
            var action = switch (choice) {
                case "1" -> DecisionReviewService.Action.ACCEPT;
                case "2" -> DecisionReviewService.Action.MODIFY;
                case "3" -> DecisionReviewService.Action.REJECT;
                default -> null;
            };
            if (action == null) { System.out.println("选项无效，仍待审核。"); return; }
            String modified = action == DecisionReviewService.Action.MODIFY
                    ? readReviewLine(scanner, "你已选择编辑文字。请输入修改后的完整决策内容（此处不是选项，不能只填 1、2、3）：") : null;
            if (action == DecisionReviewService.Action.MODIFY && (!DecisionReviewService.hasDecisionContent(modified)
                    || modified.equals(current.originalText()))) {
                System.out.println("未提交，仍待审核。修改内容需填写实际决策文字，不能只填菜单数字；如不改原稿，请重新审核并选择 1。");
                return;
            }
            String reviewer = readReviewLine(scanner, "审核人姓名（本机原型自报姓名，尚未验证平台身份）：");
            String reason = readReviewLine(scanner, "审核理由：");
            if (reviewer == null || reviewer.isBlank() || reason == null || reason.isBlank()) {
                System.out.println("审核信息不完整；未提交，仍待审核。");
                return;
            }
            String accepted = action == DecisionReviewService.Action.MODIFY ? modified : current.originalText();
            System.out.println("待提交处理：" + reviewAction(action) + "；审核人：" + reviewer + "；理由：" + reason);
            if (action != DecisionReviewService.Action.REJECT) {
                System.out.println("原始草稿：" + current.originalText() + "\n最终将入库的文本：" + accepted);
            }
            String confirm = readReviewLine(scanner, action == DecisionReviewService.Action.REJECT
                    ? "输入“确认”提交否决；其他输入取消：" : "输入“确认”提交审核并加入当前项目知识库；其他输入取消：");
            if (!"确认".equals(confirm)) { System.out.println("已取消，仍待审核。"); return; }
            decisions.review(id, action, reviewer, reason, modified);
            if (action == DecisionReviewService.Action.REJECT) {
                System.out.println("已记录否决；没有加入知识库，也未调用向量模型。审核记录仅在本次运行内存。");
                return;
            }
            publishDecision(id, service);
        } else {
            String id = fileArgument(command, "/publish-decision");
            if (id.isBlank()) { System.out.println("用法：/publish-decision 已采纳的决策编号"); return; }
            publishDecision(id, service);
        }
    }

    private void publishDecision(String id, KnowledgeService service) {
        var current = decisions.get(id);
        if (current.status() != DecisionReviewService.Status.APPROVED && current.status() != DecisionReviewService.Status.PUBLISHED) {
            System.out.println("该条未被人工采纳，不能入库。请先 /review-decision 编号。");
            return;
        }
        try {
            var result = decisions.publish(id, service);
            System.out.println((result.imported().unchanged() ? "已经入库，跳过重复向量化：" : "已加入当前项目知识库：")
                    + id + "，" + result.imported().chunks() + " 个片段。可用 /ask 查询，/docs 查看来源。");
            System.out.println(storage.localPersistenceEnabled() ? "当前开启文件兼容模式，知识索引按该模式保存；审核列表仍仅在内存。"
                    : "正文、向量和审核记录仅在本次运行内存，未写入本地文件或远程数据库，退出清空。");
        } catch (IOException | RuntimeException ex) {
            System.err.println("本次入库未完成，原知识索引保持不变。审核记录仍保留在内存；尚未入库的条目保持“已采纳、未入库”。");
            System.err.println("检查向量服务、额度或存储配置后，用 /publish-decision " + id + " 重试，无需重新审核。");
            System.err.println("错误类型：" + ex.getClass().getSimpleName());
        }
    }

    private static String readReviewLine(Scanner scanner, String prompt) {
        System.out.print(prompt);
        return scanner.hasNextLine() ? scanner.nextLine().strip() : null;
    }
    private static String decisionStatus(DecisionReviewService.Status status) {
        return switch (status) {
            case PENDING_REVIEW -> "待审核、未入库";
            case APPROVED -> "已采纳、未入库";
            case REJECTED -> "已否决、未入库";
            case PUBLISHED -> "已审核并入库";
        };
    }
    private static String reviewAction(DecisionReviewService.Action action) {
        return switch (action) {
            case ACCEPT -> "采纳原稿";
            case MODIFY -> "修改后采纳";
            case REJECT -> "否决";
        };
    }
    private static void printDecision(DecisionReviewService.Candidate item) {
        System.out.println("\n决策编号：" + item.id() + "；状态：" + decisionStatus(item.status())
                + (item.simulation() ? "；模拟演示材料" : "；用户提供材料"));
        System.out.println("AI 原稿：" + item.originalText() + "\n来源文件：" + item.sourceFile() + "\n输入 SHA-256：" + item.inputSha256());
        item.sourceMessages().forEach(message -> System.out.println("原始消息 [" + message.id() + "] " + message.speaker() + "：" + message.content()));
        if (item.review() != null) {
            System.out.println("审核结论：" + reviewAction(item.review().action()) + "；审核人：" + item.review().reviewer()
                    + "；审核时间（UTC）：" + item.review().reviewedAt() + "\n理由：" + item.review().reason());
        }
        if (item.approvedText() != null) { System.out.println("采纳文本：" + item.approvedText()); }
    }
    private static void printDecisionProvenance(KnowledgeService.DecisionProvenance evidence) {
        System.out.println("  群聊决策：" + evidence.decisionId() + (evidence.simulation() ? "（模拟）" : "")
                + "；消息：" + evidence.sourceMessageIds() + "\n  审核人：" + evidence.reviewer()
                + "；处理：" + evidence.reviewAction() + "；审核时间（UTC）：" + evidence.reviewedAt()
                + "\n  审核理由：" + evidence.reason() + "\n  原始文件：" + evidence.sourceFile()
                + "；输入 SHA-256：" + evidence.inputSha256());
    }

    private static void printPersistence(String name, Path file) {
        System.out.println(file == null ? "\n" + name + "尚未持久化：本地保存已关闭，远程存储尚未接入。"
                : "\n" + name + "已保存：" + file);
    }

    private static boolean isCommand(String input, String command) {
        return input.equals(command) || input.startsWith(command + " ");
    }

    private static String fileArgument(String input, String command) {
        String file = input.substring(command.length()).strip();
        if (file.startsWith("\"") && file.endsWith("\"") && file.length() > 1) {
            return file.substring(1, file.length() - 1);
        }
        return file;
    }
}

package com.paicli.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 工具结果投影器：把一次工具输出切成「给模型 / 给界面 / 原文引用」三份。
 *
 * <p>阈值规则：
 * <ul>
 *   <li>≤ 2400 字符：原样返回（FULL），不落盘；</li>
 *   <li>≤ 12000 字符：头 1000 + 尾 1000 + 错误行（HEAD_TAIL），原文落盘；</li>
 *   <li>更大：摘要（长度/行数/错误行/开头预览）（DIGEST），原文落盘。</li>
 * </ul>
 *
 * <p>原文默认写到 {@code ~/.paicli/runs/yyyyMMdd/<tool_use_id>.txt}，
 * 可用系统属性 {@code paicli.runs.dir} 或环境变量 {@code PAICLI_RUNS_DIR} 覆盖（测试用）。
 */
public final class ToolResultProjector {
    // 压缩策略阈值
    static final int FULL_MAX_CHARS = 2_400; // 调用结果压缩字符数,如果小于这个值则原样本返回
    static final int TAIL_MAX_CHARS = 12_000; // 头尾策略上限字符数
    static final int HEAD_CHARS = 1_000; // 头截断字符数
    static final int TAIL_CHARS = 1_000; // 尾截断字符数
    static final int UI_MAX_CHARS = 4_000; // 终端展示字符数
    static final int DIGEST_PREVIEW_CHARS = 600; // 摘要保留字符
    static final int MAX_ERROR_LINES = 12; // 头尾策略保留索引行数,省略的原文在哪,如何回读
    static final int MAX_ERROR_LINE_CHARS = 200; // 单条错误行截断长度
    static final int READ_MAX_LINES = 500; // 单次回读的最多行数
    static final int DEFAULT_RETENTION_DAYS = 7; // 回读后原文保留的天数
    // Pattern:Java正则表达式,编译后的正则表达式对象
    // 筛选英文/中文错误词
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i)\\b(error|exception|failed|failure|fatal|traceback|panic)\\b|失败|错误|异常");
    // 日期格式,存储目录的日期
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 指定原文存哪个文件夹
    private static final String RUNS_DIR_PROPERTY = "paicli.runs.dir";
    private static final String RUNS_DIR_ENV = "PAICLI_RUNS_DIR";

    private static final Logger log = (Logger) LoggerFactory.getLogger(ToolResultProjector.class);

    private ToolResultProjector(){
    }

    /**
     * 对一次工具输出做投影。这是纯函数，同样的输入永远得到同样的输出。
     *
     * @param toolUseId 工具调用 ID，用于原文落盘和回读
     * @param toolName  工具名，仅用于展示/统计
     * @param rawText   工具原始输出，可为 null
     */
    // 功能入口,将长的工具输出转换为:给大模型看的内容,终端显示的内容,原文的存放地址
    public static ToolResultProjection project(String toolUseId, String toolName, String rawText){
        long started = System.nanoTime();
        String raw = rawText == null ? "" : rawText;
        int rawChars = raw.length();
        int rawLines = countLines(raw);

        // 小于触发投影阈值,直接原文返回
        if (rawChars <= FULL_MAX_CHARS) {
            ToolResultProjection.ProjectionMetrics metrics = new ToolResultProjection.ProjectionMetrics(
                    rawChars, rawLines, rawChars, rawChars,
                    estimateTokens(raw), estimateTokens(raw), System.nanoTime() - started);
            return new ToolResultProjection(
                    ToolResultProjection.SCHEMA_VERSION, toolUseId, toolName,
                    ToolResultProjection.Strategy.FULL, raw, raw, null, metrics);
        }

        ToolResultProjection.RawRef rawRef = persist(toolUseId, raw);
        boolean headTail = rawChars <= TAIL_MAX_CHARS; // 工具执行结果字符数是否触发了首尾策略
        String modelText = headTail
                ? headTail(raw, toolUseId, rawRef)
                : digest(raw, toolUseId, toolName, rawRef, rawLines); // 触发哪个压缩策略
        String uiText = truncateForUi(raw);
        // 触发了策略的评测分析
        ToolResultProjection.ProjectionMetrics metrics = new ToolResultProjection.ProjectionMetrics(
                rawChars, rawLines, modelText.length(), uiText.length(),
                estimateTokens(raw), estimateTokens(modelText), System.nanoTime() - started);
        return new ToolResultProjection(
                ToolResultProjection.SCHEMA_VERSION, toolUseId, toolName,
                headTail ? ToolResultProjection.Strategy.HEAD_TAIL : ToolResultProjection.Strategy.DIGEST,
                modelText, uiText, rawRef, metrics);
    }

    /**
     *
     * @param toolUseId
     * @param offset
     * @param limit
     * @return
     */
    // 从原文文件中获取到原始的工具执行结果信息
    public static String readRaw(String toolUseId, int offset, int limit) {
        String id = sanitize(toolUseId); // 处理id格式
        Path file = findRawFile(id); // 获取工具结果的原文文件
        if (file == null) {
            return "未找到工具结果原文: " + id + "（可能未超长被截断，或已按保留策略清理）";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int total = lines.size();
            if (total == 0) {
                return "工具结果为空: " + id;
            }
            int start = Math.max(1, offset);
            if (start > total) {
                return "行号超出范围: " + id + " 共 " + total + " 行";
            }
            int pageSize = Math.max(1, Math.min(limit, READ_MAX_LINES));
            int end = Math.min(total, start + pageSize - 1);

            StringBuilder sb = new StringBuilder();
            sb.append("tool result: ").append(id)
                    .append(" (lines ").append(start).append('-').append(end)
                    .append(" of ").append(total).append(")\n");
            for (int i = start; i <= end; i++) {
                sb.append(i).append(" | ").append(lines.get(i - 1)).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "读取工具结果失败: " + e.getMessage();
        }
    }

    // 设置了过期随时间,清理掉过期的工具执行结果(删掉超过期限的原文目录,启动时调用)
    public static int cleanupExpired(int retentionDays) {
        int days = Math.max(1, retentionDays);
        LocalDate cutoff = LocalDate.now().minusDays(days);
        Path base = runsDir();
        if (!Files.isDirectory(base)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> entries = Files.list(base)) {
            List<Path> expired = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> isExpired(dir.getFileName().toString(), cutoff))
                    .toList();
            for (Path dir : expired) {
                deleteRecursively(dir);
                removed++;
            }
        } catch (IOException e) {
            log.warn("清理过期工具结果失败: {}", e.getMessage());
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 原文存储
    // ------------------------------------------------------------------
    // 获取存放工具结果原文的根目录
    static Path runsDir() {
        String configured = System.getProperty(RUNS_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(RUNS_DIR_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".paicli", "runs");
    }

    // 统一工具调用id格式
    static String sanitize(String toolUseId) {
        String value = toolUseId == null ? "" : toolUseId.trim();
        if (value.isEmpty()) {
            return "unknown";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    // 将工具原文执行结果落盘
    private static ToolResultProjection.RawRef persist(String toolUseId, String raw) {
        try {
            Path dir = runsDir().resolve(LocalDate.now().format(DAY));
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitize(toolUseId) + ".txt");
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            return new ToolResultProjection.RawRef(file, bytes.length, countLines(raw), sha256(bytes)); // 把文件计算得出的指纹也落盘
        } catch (IOException e) {
            log.warn("工具结果原文落盘失败: {}", e.getMessage());
            return null;
        }
    }

    // 按日期目录从新到旧找第一个命中的原文文件
    private static Path findRawFile(String id) {
        Path base = runsDir();
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(base)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(dir -> dir.resolve(id + ".txt"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing((Path path) -> path.getParent().getFileName().toString()))
                    .orElse(null); // max:目录名为日期格式,按照父目录名比较取最大就相当于先匹配最新的那一天
        } catch (IOException e) {
            log.warn("查找工具结果原文失败: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isExpired(String dirName, LocalDate cutoff) {
        try {
            return LocalDate.parse(dirName, DAY).isBefore(cutoff);
        } catch (Exception e) {
            return false;
        }
    }
    // 清理掉工具执行结果原文目录
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    // 计算原文内容的指纹
    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 文本加工
    // ------------------------------------------------------------------
    // 头尾策略处理工具执行原文结果,截留头部和尾部字符串,然后中间只保留错误行
    private static String headTail(String raw, String toolUseId, ToolResultProjection.RawRef rawRef) {
        String head = raw.substring(0, HEAD_CHARS); // 截断前面做头
        String tail = raw.substring(raw.length() - TAIL_CHARS); // 截断尾部作为尾
        int omitted = raw.length() - HEAD_CHARS - TAIL_CHARS; // 剩下的未直接截留的剩下的字符数

        StringBuilder sb = new StringBuilder(raw.length() - omitted + 256);
        sb.append(head).append("\n\n");
        sb.append("...[中间省略 ").append(omitted).append(" 字符");
        if (rawRef != null) {
            sb.append("，原文已保存：").append(rawRef.path()); // 原文保存的路径
        }
        sb.append("；需要时调用 read_tool_result(tool_use_id=\"")
                .append(sanitize(toolUseId)).append("\") 查看]...\n\n");
        sb.append(tail);
        appendErrorLines(sb, raw, 6);
        return sb.toString();
    }
    // 摘要策略处理,非语义化压缩,而是结构化摘要:元信息+关键行+开头预览
    private static String digest(String raw, String toolUseId, String toolName,
                                 ToolResultProjection.RawRef rawRef, int rawLines) {
        List<String> errors = errorLines(raw);
        StringBuilder sb = new StringBuilder(512);
        sb.append("[工具结果摘要] tool=").append(toolName == null ? "" : toolName)
                .append(" tool_use_id=").append(sanitize(toolUseId)).append('\n');
        sb.append("原始长度: ").append(raw.length()).append(" 字符 / ").append(rawLines).append(" 行\n");
        sb.append("错误行: ").append(errors.size()).append(" 条\n");
        int shown = 0;
        for (String line : errors) {
            if (shown++ >= 6) {
                break;
            }
            sb.append("  ").append(line).append('\n');
        }
        sb.append("开头预览:\n").append(raw, 0, Math.min(DIGEST_PREVIEW_CHARS, raw.length())).append('\n');
        if (rawRef != null) {
            sb.append("原文: ").append(rawRef.path()).append('\n')
                    .append("可用 read_tool_result(tool_use_id=\"").append(sanitize(toolUseId))
                    .append("\", offset=1, limit=200) 分页查看。");
        } else {
            sb.append("原文未落盘（写入失败），请缩小查询范围后重试。");
        }
        return sb.toString();
    }

    private static void appendErrorLines(StringBuilder sb, String raw, int max) {
        List<String> errors = errorLines(raw);
        if (errors.isEmpty()) {
            return;
        }
        sb.append("\n\n错误行（共 ").append(errors.size()).append(" 条）:\n");
        int shown = 0;
        for (String line : errors) {
            if (shown++ >= max) {
                break;
            }
            sb.append("  ").append(line).append('\n');
        }
    }

    // 获取到原文结果的错误报错提示,形成错误行提示列表返回
    private static List<String> errorLines(String raw) {
        List<String> matched = new ArrayList<>();
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || !ERROR_PATTERN.matcher(line).find()) {
                continue;
            }
            String text = line.strip();
            if (text.length() > MAX_ERROR_LINE_CHARS) {
                text = text.substring(0, MAX_ERROR_LINE_CHARS) + "...";
            }
            matched.add("L" + (i + 1) + ": " + text);
            if (matched.size() >= MAX_ERROR_LINES) {
                break;
            }
        }
        return matched;
    }

    // 终端展示直接截断
    private static String truncateForUi(String raw) {
        if (raw.length() <= UI_MAX_CHARS) {
            return raw;
        }
        return raw.substring(0, UI_MAX_CHARS) + "\n...(已截断，共 " + raw.length() + " 字符)";
    }

    // 行数统计
    static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return text.endsWith("\n") ? lines - 1 : lines;
    }

    /**
     * 粗略 token 估算：ASCII 约 4 字符/token，CJK 约 1.5 字符/token。
     * 仅用于离线对比与趋势，真实成本以 provider 返回的 usage 为准。
     */
    // 按字符估算token
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, (int) Math.ceil(other / 4.0 + cjk / 1.5));
    }
}
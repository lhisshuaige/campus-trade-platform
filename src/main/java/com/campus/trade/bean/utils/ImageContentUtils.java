package com.campus.trade.bean.utils;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图片内容真伪校验。
 *
 * 一句话说清为什么后缀名不够：<b>后缀名是客户端自报的，文件头是内容自己写的</b>。
 * 把 calc.exe 改名成 photo.jpg 上传，只看后缀的校验会一路放行；而文件头（magic number）
 * 是格式规范规定的固定字节，伪造它要求攻击者真的先写出一个合法图片头。
 * 本站上传目录是被 CorsConfig 的 addResourceHandlers 直接当静态资源对外提供的，
 * 一旦混进可执行/脚本内容，风险就不再是"库里多了一条脏数据"。
 *
 * 两道检查各有分工，不是重复劳动：
 * 1) 文件头：挡"根本不是这个格式"（改名伪装）—— 纯字节比较，成本几十纳秒
 * 2) ImageIO 解码：挡"头对了但内容是坏的/截断的" —— 真去解一遍，成本一次解码
 * 只有第 2 道对 webp 做不到（JDK 没有 WebP 解码器），所以按格式跳过；
 * 为它引一个第三方解码器，换来的只是"多一层确认"，而伪装威胁在第 1 道已经被挡掉，不值当。
 */
@Slf4j
@Component
public class ImageContentUtils {

    /**
     * 允许的图片后缀白名单。收在这里而不是写在 Controller 里：
     * 后缀表与下面的文件头表必须一一对应，分在两个文件里迟早漂移（加后缀忘了加签名 = 该格式永久 400）
     */
    public static final Set<String> ALLOWED_SUFFIX = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    /**
     * 给用户的“支持哪些格式”提示（无点、按字典序固定拼出来）。
     * 从白名单派生而不是手写第二串字符串：否则加了格式却忘了改文案，文档与行为又不一致
     */
    public static final String ALLOWED_SUFFIX_HINT = ALLOWED_SUFFIX.stream()
            .map(suffix -> suffix.substring(1))
            .sorted()
            .collect(Collectors.joining("/"));

    // 嗅探字节数：取最长签名的末尾（webp 的第二段落在第 8~11 字节）再留余量；
    // 刻意远小于 spring.servlet.multipart 的单文件上限，读这几个字节不会把大文件搬进内存
    private static final int SNIFF_SIZE = 16;

    /** 文件头里的一个必须命中的片段 */
    private record Fragment(int offset, byte[] bytes) {
    }

    /** 一种格式的一条可接受规则：规则内所有片段都要对上 */
    private record Rule(List<Fragment> fragments) {
    }

    // 下面五个签名常量取自各格式的规范固定头；提成常量而不是内联进 Map.of，
    // 是因为 Map.of 里再套 new Rule(List.of(new Fragment(...))) 要叠五层括号，人肉数括号必错
    // JPEG：FF D8 FF（SOI 标记 + 第一个字段的开始字节）
    private static final Rule JPEG = rule(fragment(0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
    // PNG：固定 8 字节头，含 0x1A 这个 DOS 文件结束符 —— 正是它让"拿文本文件冒充 PNG"必然失败
    private static final Rule PNG = rule(fragment(0,
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}));
    // GIF 有 87a / 89a 两个版本头，所以是两条规则取其一
    private static final Rule GIF_87A = rule(fragment(0, "GIF87a"));
    private static final Rule GIF_89A = rule(fragment(0, "GIF89a"));
    // WEBP：前 4 字节 RIFF、第 8~11 字节 WEBP，一条规则里两个片段；
    // 中间第 4~7 字节是文件长度，任何值都合法，所以不能比
    private static final Rule WEBP = rule(fragment(0, "RIFF"), fragment(8, "WEBP"));

    // 后缀 → 可接受的规则（任一条命中即算该格式）。做成数据而不是 if-else：
    // 加一种格式只是多一行签名，不用改判定逻辑
    private static final Map<String, List<Rule>> MAGIC = Map.of(
            ".jpg", List.of(JPEG),
            ".jpeg", List.of(JPEG),
            ".png", List.of(PNG),
            ".gif", List.of(GIF_87A, GIF_89A),
            ".webp", List.of(WEBP));

    // JDK 自带解码器能真解开的格式；webp 不在其中，只校到文件头为止（理由见类注释）
    private static final Set<String> DECODABLE = Set.of(".jpg", ".jpeg", ".png", ".gif");

    /**
     * 校验"这个文件真的是它声称的那种图片"，不是则抛 PARAM_ERROR。
     *
     * 提示语里刻意不写"检测到的是 zip/exe"、也不写"期望的文件头是什么"——
     * 那等于给试探者一张进度条，告诉他差哪一步就过了。
     *
     * @param suffix 已转小写、含点的后缀（由调用方从原始文件名切出来）
     */
    public void assertRealImage(String suffix, MultipartFile file) {
        byte[] head = readHead(file);
        if (!matchesMagic(suffix, head)) {
            //十六进制头只进日志：排查时它是关键线索（用户到底传了什么），响应里它是攻击面
            log.info("文件头与后缀不符 suffix={} head={}", suffix, hexOf(head));
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件内容与后缀名不一致，请重新选择图片");
        }
        if (!DECODABLE.contains(suffix)) {
            return;
        }
        // 头对了再真解一遍：挡"合法图片头 + 后面全是垃圾字节"这种半截文件。
        // ImageIO 的失败形态不止一种（返回 null / 抛 IIOException / 抛运行时异常），所以三种都收口成同一句话
        BufferedImage image;
        try (InputStream in = file.getInputStream()) {
            image = ImageIO.read(in);
        } catch (Exception e) {
            log.warn("图片解码失败 suffix={} size={}", suffix, file.getSize(), e);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片无法解析，可能已损坏，请重新上传");
        }
        if (image == null) {
            log.info("图片解码返回空（格式不受支持或内容损坏） suffix={} head={}", suffix, hexOf(head));
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片无法解析，可能已损坏，请重新上传");
        }
    }

    /** 该后缀是否在支持范围内（白名单外由调用方给"仅支持图片"的提示） */
    public static boolean isSupported(String suffix) {
        return ALLOWED_SUFFIX.contains(suffix);
    }

    private byte[] readHead(MultipartFile file) {
        // 用 getInputStream 而不是 getBytes：后者会把整个文件读进内存，10MB 上限下并发传几张就是几百 MB
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(SNIFF_SIZE);
        } catch (IOException e) {
            // 到这里不是用户的问题而是服务端读不出临时文件（被清理/磁盘故障），
            // 报 400 会把人引去反复重传，报 500 才对得上"这边坏了"的语义；细节只记日志不外泄
            log.error("读取上传文件头失败 name={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件读取失败，请稍后重试");
        }
    }

    private boolean matchesMagic(String suffix, byte[] head) {
        List<Rule> rules = MAGIC.get(suffix);
        if (rules == null) {
            // 走到这里说明 ALLOWED_SUFFIX 与 MAGIC 两份口径漂了（加了后缀忘了加签名）
            log.error("后缀没有对应的文件头签名 suffix={}", suffix);
            return false;
        }
        for (Rule rule : rules) {
            boolean hit = true;
            for (Fragment fragment : rule.fragments()) {
                if (!regionMatches(head, fragment)) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private boolean regionMatches(byte[] head, Fragment fragment) {
        byte[] expect = fragment.bytes();
        int end = fragment.offset() + expect.length;
        // 文件比签名还短：不是图片（0 字节文件已被上游 file.isEmpty 拦掉，这里防的是"只写了半个头"）
        if (head.length < end) {
            return false;
        }
        for (int i = 0; i < expect.length; i++) {
            if (head[fragment.offset() + i] != expect[i]) {
                return false;
            }
        }
        return true;
    }

    // 日志用的十六进制预览；不打印原始内容，避免把上传内容的一部分刷进日志文件
    private String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    // ---------- 三个静态工厂：把签名表写成"数据"，而不是写成括号金字塔 ----------

    private static Rule rule(Fragment... fragments) {
        return new Rule(List.of(fragments));
    }

    private static Fragment fragment(int offset, byte[] bytes) {
        return new Fragment(offset, bytes);
    }

    private static Fragment fragment(int offset, String ascii) {
        return new Fragment(offset, ascii.getBytes(StandardCharsets.US_ASCII));
    }
}

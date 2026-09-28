package com.campus.trade.bean.utils;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * UGC 内容审核（评论、商品标题/描述、举报理由都走这一个入口）。
 *
 * 拦截策略选「直接拒绝发布」而不是「打码后照样入库」：
 * 入库的脏内容会进缓存、进排行榜、被第二个人读到，那时再改就要连带做一轮缓存失效，
 * 审核语义从「不让脏数据产生」退化成「事后补救」，成本完全不是一个量级。
 *
 * 匹配用最朴素的 contains 遍历：词库几十条、评论上限 200 字，这个量级用 AC 自动机是给自己找麻烦。
 * 词库上百条再换 DFA（一整棵 Trie 一次扫描完成匹配），只改这个类的内部，调用方一行不动。
 *
 * 词库来源是配置项，不硬编码在代码里：敏感词是要随治理口径变的，
 * 为一个词改一次代码、重发一次版，是典型的把策略写死进实现。
 */
@Slf4j
@Component
public class ContentAuditUtils {

    /** 评论内容长度上限：与 CommentAddDTO 的 @Size 同一口径，DB 侧是 TEXT 不拦长度 */
    public static final int COMMENT_MAX_LENGTH = 200;

    //关闭开关留给压测/演示：批量灌数据时不必为造数据先想一套"不含敏感词的脏话"
    @Value("${app.content.audit-enabled:true}")
    private boolean auditEnabled;

    //逗号分隔的词表；配置缺省为空 = 不拦截（不把词表当成代码的一部分，也不假装有一套通用词库）
    @Value("${app.content.sensitive-words:}")
    private List<String> sensitiveWords = new ArrayList<>();

    /**
     * 校验一段用户输入的文本，命中即抛 PARAM_ERROR。
     *
     * @param text  待检文本，null/空白直接放过（必填由 DTO 的 @NotBlank 负责，两件事不混在这里）
     * @param label 只进日志的出处标记（如 comment/goods.title），不出现在给用户的提示里
     */
    public void assertClean(String text, String label) {
        if (!auditEnabled || text == null || text.isBlank() || sensitiveWords == null || sensitiveWords.isEmpty()) {
            return;
        }
        //大小写统一后再比：词表里有 "abc" 时不该放过 "Abc"（只对拉丁字符有意义，中文本来就没有大小写）
        String normalized = text.toLowerCase();
        for (String word : sensitiveWords) {
            if (word == null || word.isBlank()) {
                continue;
            }
            String target = word.trim().toLowerCase();
            if (normalized.contains(target)) {
                //命中了哪个词只记日志：回给调用方等于送给试探者一张"哪些词不能用"的清单
                log.info("内容审核拦截 label={} hit={}", label, word);
                throw new BusinessException(ErrorCode.PARAM_ERROR, "内容包含违规信息，请修改后再提交");
            }
        }
    }

    /** 当前生效的词条数，只给日志/自检用 */
    public int wordCount() {
        return sensitiveWords == null ? 0 : sensitiveWords.size();
    }
}

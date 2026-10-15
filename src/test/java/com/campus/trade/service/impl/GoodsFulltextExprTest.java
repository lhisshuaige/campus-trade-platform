package com.campus.trade.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 全文检索表达式拼装的断言（P3-11，被测对象是 P3-2 的 buildFulltextExpr）。
 * <p>
 * 这个方法定成**包可见 static**（而不是 private）就是为这里存在的：它是少数几个
 * "不需要 Spring 容器、不需要 DB、也不需要 Redis"就能把规则全验完的入口。
 * 下面每一条断言都对应一次真打 MySQL 拿到的数字，不是在描述代码，而是在描述**外部世界的行为**：
 * <ul>
 *   <li>{@code MATCH('+书')} 命中 0 行、{@code MATCH('+书*')} 命中 625 行 —— ngram 索引词是定长二字词，
 *       单字关键词不补通配就一个索引词都匹配不上，所以那个 {@code *} 不是可选优化而是必需项；</li>
 *   <li>{@code MATCH('+74604*')}（剔掉 "-" 后拼出来的）命中 0 行，而标题里确实写着 74-604；
 *       {@code MATCH('+74-604*')} 命中 720 行（"-" 被 BOOLEAN MODE 当成排除），
 *       只有 {@code LIKE '%74-604%'} 的 625 行是用户想要的 —— 这就是第一版"剔除操作符"被推翻的现场，
 *       现规则改为**含保留字符就整条交回 LIKE**；</li>
 *   <li>词与词之间必须用 {@code +}（AND），因为 BOOLEAN MODE 默认是 OR，"教材 手机"会被搜成"任一命中"。</li>
 * </ul>
 */
class GoodsFulltextExprTest {

    @Test
    @DisplayName("普通关键词：补 AND 前缀 + 通配后缀（那个 * 少掉就会搜不到，不是性能问题）")
    void normalKeywordGetsAndPrefixAndWildcardSuffix() {
        assertEquals("+教材*", GoodsServiceImpl.buildFulltextExpr("教材"));
        assertEquals("+书*", GoodsServiceImpl.buildFulltextExpr("书"));
    }

    @Test
    @DisplayName("多词：按空格切分后逐词 AND，多余空格折叠")
    void multipleWordsBecomeAnAndExpression() {
        assertEquals("+四级* +词汇*", GoodsServiceImpl.buildFulltextExpr("四级 词汇"));
        assertEquals("+二手* +自行车*", GoodsServiceImpl.buildFulltextExpr("  二手   自行车  "));
    }

    @Test
    @DisplayName("含 BOOLEAN MODE 保留字符的关键词一律交回 LIKE（返回 null 表示走不了全文）")
    void reservedCharsFallBackToLike() {
        // 型号里带连字符：实测"剔掉它"和"原样传"两种做法得到的都不是用户要的结果
        assertNull(GoodsServiceImpl.buildFulltextExpr("74-604"));
        // 语言名带加号
        assertNull(GoodsServiceImpl.buildFulltextExpr("C++"));
        // 括号、尖括号、波浪号、星号、引号各自都是操作符
        assertNull(GoodsServiceImpl.buildFulltextExpr("((((("));
        assertNull(GoodsServiceImpl.buildFulltextExpr("(教材)"));
        assertNull(GoodsServiceImpl.buildFulltextExpr("教材*"));
        assertNull(GoodsServiceImpl.buildFulltextExpr("a>b"));
        assertNull(GoodsServiceImpl.buildFulltextExpr("a~b"));
        assertNull(GoodsServiceImpl.buildFulltextExpr("二手 -新"));
        assertNull(GoodsServiceImpl.buildFulltextExpr("教材\"书\""));
    }

    @Test
    @DisplayName("回退的判定只看 ASCII 保留字符，中文标点与全角符号不该被误伤")
    void fullWidthPunctuationIsNotReserved() {
        // 这条断言管的是"过度回退"：如果把非 ASCII 标点也当保留字符，全项目的中文标题就永远走不了索引，
        // 而 P3-2 的性能收益正是在中文标题上
        assertEquals("+教材（二手）*", GoodsServiceImpl.buildFulltextExpr("教材（二手）"));
        assertEquals("+九成新，可小刀*", GoodsServiceImpl.buildFulltextExpr("九成新，可小刀"));
    }

    @Test
    @DisplayName("空串/纯空格不是返回 null，而是拼出无意义的 \"+*\" —— 它没造成故障只因为调用方先挡了")
    void blankKeywordProducesGarbageSoCallerMustGuardIt() {
        // 这是一条**如实记录现状**的断言，而不是「期望行为」：buildFulltextExpr("") 得到 "+*"。
        // 生产路径上它不可达，因为 doPageGoods 先做了 keyword.isBlank() 判断。
        // 把它写成断言而不是注释的理由：将来谁把那层 isBlank 拿掉，这里会红，
        // 而不是线上出现"一个空格搜出整张表"这种既没有报错也没有人报的问题
        assertEquals("+*", GoodsServiceImpl.buildFulltextExpr(""));
        assertEquals("+*", GoodsServiceImpl.buildFulltextExpr("   "));
    }
}

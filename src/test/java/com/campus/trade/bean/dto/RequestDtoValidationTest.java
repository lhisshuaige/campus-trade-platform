package com.campus.trade.bean.dto;

import com.campus.trade.bean.dto.request.goods.GoodsAddDTO;
import com.campus.trade.bean.dto.request.user.UserRegisterDTO;
import com.campus.trade.bean.entity.Goods;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入参校验第一层（DTO 注解）的断言（P3-11，配合第五批 P2-1~P2-3）。
 * <p>
 * 这里刻意**不启动 Spring 容器**：{@code Validation.buildDefaultValidatorFactory()} 拿到的
 * 就是 Hibernate Validator 本身，而 Spring 只做了一件"把校验接到参数绑定上"的事。
 * 也就是说这四层口径里最靠外那一层完全可测，不需要 MySQL、不需要 Redis ——
 * 这正是它该第一批补上的原因。
 * <p>
 * 有几条断言看着像在断言"没报错"，其实方向相反：它们锁的是**某个规则不在这一层**这个决定
 * （跨字段的期望价、成色取值域）。这类"缺席断言"的价值是让以后想"顺手加个注解"的人
 * 必须先回答"这条规则和 DB 那条 CHECK 谁是唯一口径"。
 */
class RequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** 只看消息文本：断言"拦住了什么"比断言"违规几条"更能说明是哪条规则生效 */
    private static Set<String> messagesOf(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static UserRegisterDTO register(String username, String password, String phone) {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setPhone(phone);
        return dto;
    }

    @Test
    @DisplayName("合法注册请求零违规 —— 这条是其余断言的基准，它红说明是测试写错了")
    void normalRegisterPasses() {
        assertTrue(messagesOf(register("zhang_san", "abc123", null)).isEmpty());
        assertTrue(messagesOf(register("张三丰", "abc123", "13800138000")).isEmpty());
    }

    @Test
    @DisplayName("用户名：下限 3 是业务口径而不是列宽，非法字符集与超长各自走各自的消息")
    void usernameRules() {
        assertTrue(messagesOf(register("张", "abc123", null)).stream().anyMatch(m -> m.contains("用户名长度")));
        assertTrue(messagesOf(register("ab", "abc123", null)).stream().anyMatch(m -> m.contains("用户名长度")));
        assertTrue(messagesOf(register("user@name", "abc123", null))
                .stream().anyMatch(m -> m.contains("用户名只能用")));
        assertTrue(messagesOf(register("a".repeat(21), "abc123", null)).stream().anyMatch(m -> m.contains("用户名长度")));
        // 中文与下划线是允许的：用户名规则只禁符号，不把中文姓名挡在外面
        assertTrue(messagesOf(register("zhang_san", "abc123", null)).isEmpty());
    }

    @Test
    @DisplayName("两个字的中文姓名会被长度拦下 —— 已知取值，不是 bug，但它必须被写下来")
    void twoCharChineseNameIsRejectedByMinLength() {
        // 写这条的理由：它是本测试第一次运行时真实报出来的失败。「张三」这种两字实名
        // 在 USERNAME_MIN_LENGTH=3 下无法注册 —— 长度单位是**字符数**而不是显示宽度，
        // 中文一个字就是 1。这不是写错的正则，是一个需要人拍板的口径（放开到 2 会同时
        // 放开两字母数字名），所以把它固定成断言而不是删掉：
        // 将来若决定放开到 2，这条会红，提醒同时改 DB 与文档
        assertTrue(messagesOf(register("张三", "abc123", null))
                .stream().anyMatch(m -> m.contains("用户名长度")));
        // 而三字中文姓名是通的：说明拦它的不是 @Pattern 的字符集，只有长度
        assertTrue(messagesOf(register("张三丰", "abc123", null)).isEmpty());
    }

    @Test
    @DisplayName("密码只禁空白 —— 而这条禁得起解释：粘贴带上首尾空格，用户就永久登不上")
    void passwordRejectsWhitespaceButNotWeakness() {
        assertTrue(messagesOf(register("zhangsan", "12 3456", null))
                .stream().anyMatch(m -> m.contains("密码不能包含空格")));
        // "123456" 这种弱密码是**放过**的：在线爆破已经被"错 5 次锁 15 分钟"挡住，
        // 弱密码的真实威胁是库泄密后的离线撞库，那时救命的是 BCrypt 的 cost factor
        assertTrue(messagesOf(register("zhangsan", "123456", null)).isEmpty());
        assertTrue(messagesOf(register("zhangsan", "12345", null)).stream().anyMatch(m -> m.contains("密码长度")));
    }

    @Test
    @DisplayName("手机号：不传（null）放过、传空串拦下 —— 「没填」只允许一种表示法")
    void phoneHasExactlyOneWayToMeanNotFilled() {
        assertTrue(messagesOf(register("zhangsan", "abc123", null)).isEmpty());
        assertTrue(messagesOf(register("zhangsan", "abc123", "13800138000")).isEmpty());
        assertTrue(messagesOf(register("zhangsan", "abc123", ""))
                .stream().anyMatch(m -> m.contains("手机号格式")));
        assertTrue(messagesOf(register("zhangsan", "abc123", "12345678901"))
                .stream().anyMatch(m -> m.contains("手机号格式")));
        assertTrue(messagesOf(register("zhangsan", "abc123", "1380013800"))
                .stream().anyMatch(m -> m.contains("手机号格式")));
    }

    private static GoodsAddDTO goods(String title, BigDecimal price, BigDecimal expectPrice, String condition) {
        GoodsAddDTO dto = new GoodsAddDTO();
        dto.setCategoryId(1L);
        dto.setTitle(title);
        dto.setPrice(price);
        dto.setExpectPrice(expectPrice);
        dto.setConditionLevel(condition);
        return dto;
    }

    @Test
    @DisplayName("价格的 @Digits 不是装饰：三位小数不拦就会被 MySQL 四舍五入成 2.00 存进去")
    void priceDigitsAreEnforced() {
        assertFalse(messagesOf(goods("教材", new BigDecimal("1.999"), null, null)).isEmpty());
        assertTrue(messagesOf(goods("教材", new BigDecimal("1.99"), null, null)).isEmpty());
        // 整数位 9 位（列是 DECIMAL(10,2)，整数最多 8 位）
        assertFalse(messagesOf(goods("教材", new BigDecimal("123456789"), null, null)).isEmpty());
        assertFalse(messagesOf(goods("教材", new BigDecimal("-0.01"), null, null)).isEmpty());
        // 0 元是**合法**的：免费送在校园场景里是真实业务，且下单不涉及资金
        assertTrue(messagesOf(goods("教材", BigDecimal.ZERO, null, null)).isEmpty());
    }

    @Test
    @DisplayName("标题超长在 DTO 拦（否则是 1406 那句最难懂的 400）；但成色取值域**不在**这里拦")
    void titleLengthIsHereWhileConditionDomainIsNot() {
        assertFalse(messagesOf(goods("a".repeat(Goods.TITLE_MAX_LENGTH + 1),
                BigDecimal.ONE, null, null)).isEmpty());
        // 成色"九成新"不在白名单里，却**能通过 DTO**：取值域是 Service 的 checkGoodsContent 在管，
        // DB 的 chk_goods_condition 兜底。这一层只校列宽，两处不重复一份口径
        assertTrue(messagesOf(goods("教材", BigDecimal.ONE, null, "九成新")).isEmpty());
        // 但超出列宽必须被拦下（@Size 用的是 CONDITION_MAX_LENGTH，与列宽同源）
        assertFalse(messagesOf(goods("教材", BigDecimal.ONE, null, "a".repeat(Goods.CONDITION_MAX_LENGTH + 1))).isEmpty());
    }

    @Test
    @DisplayName("期望价高于标价：DTO 层**故意**放过去，跨字段规则归 Service（给人话 400 而不是 DB 的 500）")
    void crossFieldRuleIsDeliberatelyNotHere() {
        Set<String> messages = messagesOf(goods("教材", new BigDecimal("10"), new BigDecimal("200"), null));
        assertEquals(0, messages.size(),
                "若这条开始失败，说明有人给 DTO 加了 @AssertTrue —— 先回答 expectPrice 的唯一口径在哪");
    }

    @Test
    @DisplayName("必填项缺失被拦：categoryId 不传就不是「商品发布请求」")
    void requiredFieldsAreRequired() {
        GoodsAddDTO dto = goods("教材", BigDecimal.ONE, null, null);
        dto.setCategoryId(null);
        assertFalse(messagesOf(dto).isEmpty());

        GoodsAddDTO blankTitle = goods("教材", BigDecimal.ONE, null, null);
        blankTitle.setTitle("   ");
        assertTrue(messagesOf(blankTitle).stream().anyMatch(m -> m.contains("商品标题不能为空")),
                "纯空格标题要被判空拦下，否则 @NotBlank 等于没写");
    }
}

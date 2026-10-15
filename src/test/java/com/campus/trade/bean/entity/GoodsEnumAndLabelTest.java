package com.campus.trade.bean.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goods 的取值域与标签口径断言（P3-11）。
 * <p>
 * 这几条断言都是"DB 与看板会替我报错"的那类问题，把它们前移到测试里的理由：
 * 状态标签与成色白名单各有第二份 copies（{@code chk_goods_status}、{@code chk_goods_condition}、
 * 统计接口返回给前端的中文），三份口径只要漂一次，报错形态都是"数字对不上"而不是"接口挂了"，
 * 而数字对不上永远不会有人当天发现
 */
class GoodsEnumAndLabelTest {

    @Test
    @DisplayName("状态 → 中文标签：三个合法值各有一个标签，且与 chk_goods_status 的 (0,1,2) 同集")
    void statusLabelsCoverExactlyTheAllowedValues() {
        assertEquals("已下架", Goods.statusLabel(Goods.STATUS_OFF));
        assertEquals("在售", Goods.statusLabel(Goods.STATUS_ON_SALE));
        assertEquals("已售出", Goods.statusLabel(Goods.STATUS_SOLD));

        // 标签表的 key 集合必须**正好**等于状态常量集合：多一个是死标签，少一个是看板上的空白柱
        Set<Integer> allowed = new HashSet<>(Goods.STATUS_LABELS.keySet());
        assertEquals(Set.of(Goods.STATUS_OFF, Goods.STATUS_ON_SALE, Goods.STATUS_SOLD), allowed);
    }

    @Test
    @DisplayName("脏状态值给「未知」而不是抛异常 —— 统计是读侧，不能为一行历史数据打死整个看板")
    void unknownStatusDegradesToLabelInsteadOfThrowing() {
        // 这条存在的真实原因：STATUS_LABELS 是 Map.of 造的，它对 null key 的行为是**抛 NPE**而不是返 null。
        // statusLabel() 里那层三元判断就是为它加的，删掉那段"看起来多余的判空"不会编译报错，
        // 只会在某天下班后让 /statistics/goodsStatus 变成 500
        assertEquals("未知", Goods.statusLabel(null));
        assertEquals("未知", Goods.statusLabel(99));
        assertDoesNotThrow(() -> Goods.statusLabel(null));
    }

    @Test
    @DisplayName("成色五档不许重复、不许空、每档都装得进 condition_level 那一列")
    void conditionWhitelistIsUsable() {
        assertEquals(5, Goods.CONDITIONS.size());
        assertEquals(new HashSet<>(Goods.CONDITIONS).size(), Goods.CONDITIONS.size(), "成色档位不能重复");
        for (String condition : Goods.CONDITIONS) {
            assertFalse(condition.isBlank(), "成色档位不能是空白");
            // 真实的故障形态：以后加一档"九九新以上"如果超了列宽，MySQL 报 1406 或被静默截断，
            // 而这发生在写入时、不在编译时 —— 所以在这里断言
            assertTrue(condition.length() <= Goods.CONDITION_MAX_LENGTH,
                    "成色档位超出列宽 condition_level VARCHAR(" + Goods.CONDITION_MAX_LENGTH + ")");
        }
    }

    @Test
    @DisplayName("金额口径两个常量之和 = DECIMAL(10,2) 的精度，改列宽必须同时改这里")
    void priceDigitsMatchTheColumnDefinition() {
        // DECIMAL(M,D)：M 是总位数、D 是小数位，整数位最多 M-D。列是 (10,2) → 整数最多 8 位
        assertEquals(10, Goods.PRICE_INTEGER_DIGITS + Goods.PRICE_FRACTION_DIGITS);
        assertEquals(2, Goods.PRICE_FRACTION_DIGITS);
        // 为什么不省掉 fraction 限制：@Digits 只管 fraction 时，1.999 会被 MySQL 四舍五入成 2.00 存进去，
        // 用户看到的是"我填的价被改了"
    }

    @Test
    @DisplayName("三个状态常量互不相等，且「已售出」是那个被订单占住的值")
    void statusConstantsAreDistinct() {
        assertNotEquals(Goods.STATUS_OFF, Goods.STATUS_ON_SALE);
        assertNotEquals(Goods.STATUS_ON_SALE, Goods.STATUS_SOLD);
        // 逻辑删除后"已售出不可删"靠的是 status <> STATUS_SOLD 的条件删除（fk_order_goods 的 RESTRICT 已经
        // 不会被触发了），所以这个值的含义必须由常量而不是字面量 2 来表达
        assertEquals(2, Goods.STATUS_SOLD);
    }
}

package com.campus.trade.service.impl;

import com.campus.trade.bean.entity.Order;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * L2：删除已取消订单的三道闸门 —— 存在性、归属、终态。
 * 归属校验排在状态校验之前是刻意的：无关的人只能听到"无权操作"，
 * 借这个接口探测"某单存在且处于某状态"的通道必须焊死。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("L2 OrderServiceImpl：删除已取消订单的闸门")
class OrderDeleteCancelledTest {

    @Mock private OrderMapper orderMapper;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new OrderServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
    }

    private Order order(Long id, Long buyerId, Long sellerId, int status) {
        Order o = new Order();
        o.setId(id);
        o.setBuyerId(buyerId);
        o.setSellerId(sellerId);
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("已取消 + 本人（买家或卖家皆可）→ 物理删")
    void cancelledOwnedByBuyerDeletes() {
        doReturn(order(7L, 11L, 22L, Order.STATUS_CANCELLED)).when(service).getById(7L);

        service.deleteCancelledOrder(7L, 11L);

        verify(service).removeById(7L);
    }

    @Test
    @DisplayName("已取消 + 卖家 → 同样可删（双方对称）")
    void cancelledOwnedBySellerDeletes() {
        doReturn(order(7L, 11L, 22L, Order.STATUS_CANCELLED)).when(service).getById(7L);

        service.deleteCancelledOrder(7L, 22L);

        verify(service).removeById(7L);
    }

    @Test
    @DisplayName("非买卖双方 → 403，且不暴露该单的存在与状态")
    void strangerForbiddenBeforeStatusCheck() {
        doReturn(order(7L, 11L, 22L, Order.STATUS_CANCELLED)).when(service).getById(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteCancelledOrder(7L, 999L));
        assertTrue(ex.getMsg().contains("无权"));
        verify(service, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("进行中（待确认/已确认/已完成）→ 400，只有已取消能删")
    void nonCancelledRejected() {
        doReturn(order(7L, 11L, 22L, Order.STATUS_COMPLETED)).when(service).getById(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteCancelledOrder(7L, 11L));
        assertTrue(ex.getMsg().contains("已取消"));
        verify(service, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("订单不存在 → 404 语义（重复点删除的第二次就会走到这）")
    void missingOrderNotFound() {
        doReturn(null).when(service).getById(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteCancelledOrder(7L, 11L));
        assertTrue(ex.getMsg().contains("不存在"));
        verify(service, never()).removeById(anyLong());
    }
}

package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order_item_number")
public class OrderItemNumber {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;
    private String numberZone;
    private String number;
    private BigDecimal amountAlloc;

    private LocalDateTime createdAt;
}

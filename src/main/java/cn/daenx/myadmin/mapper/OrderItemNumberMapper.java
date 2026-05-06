package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.OrderItemNumber;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderItemNumberMapper extends BaseMapper<OrderItemNumber> {

    @Insert("<script>" +
            "INSERT INTO t_order_item_number (item_id, number_zone, number, amount_alloc, created_at) VALUES " +
            "<foreach collection='numbers' item='number' separator=','>" +
            "(#{number.itemId}, #{number.numberZone}, #{number.number}, #{number.amountAlloc}, NOW(3))" +
            "</foreach>" +
            "</script>")
    int insertBatchRecords(@Param("numbers") List<OrderItemNumber> numbers);
}

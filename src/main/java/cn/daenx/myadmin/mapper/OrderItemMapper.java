package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.OrderItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    @Update("UPDATE t_order_item SET stat_applied = 1 WHERE id = #{id} AND stat_applied = 0")
    int tryApplyStat(Long id);
}

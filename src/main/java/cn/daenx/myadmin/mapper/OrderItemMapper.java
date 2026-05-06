package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.OrderItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    @Insert("<script>" +
            "INSERT INTO t_order_item " +
            "(raw_id, batch_id, item_no, lottery_category, game_type, play_type, issue_no, issue_key, " +
            "bet_count, group_count, multiple, total_amount, amount_alloc_mode, raw_text, stat_applied, created_at) VALUES " +
            "<foreach collection='items' item='item' separator=','>" +
            "(#{item.rawId}, #{item.batchId}, #{item.itemNo}, #{item.lotteryCategory}, #{item.gameType}, " +
            "#{item.playType}, #{item.issueNo}, #{item.issueKey}, #{item.betCount}, #{item.groupCount}, " +
            "#{item.multiple}, #{item.totalAmount}, #{item.amountAllocMode}, #{item.rawText}, " +
            "#{item.statApplied}, NOW(3))" +
            "</foreach>" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "items.id", keyColumn = "id")
    int insertBatchRecords(@Param("items") List<OrderItem> items);

    @Update("UPDATE t_order_item SET stat_applied = 1 WHERE id = #{id} AND stat_applied = 0")
    int tryApplyStat(Long id);
}

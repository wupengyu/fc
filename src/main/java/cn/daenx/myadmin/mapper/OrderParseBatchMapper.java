package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.OrderParseBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderParseBatchMapper extends BaseMapper<OrderParseBatch> {

    @Update("UPDATE t_order_parse_batch " +
            "SET is_effective = 0 " +
            "WHERE raw_id = #{rawId} AND id <> #{keepId} AND is_effective = 1")
    int markOthersNotEffective(@Param("rawId") Long rawId, @Param("keepId") Long keepId);

    @Select("SELECT COUNT(*) FROM t_order_parse_batch b " +
            "JOIN ( " +
            "  SELECT b2.raw_id, MAX(b2.id) AS id " +
            "  FROM t_order_parse_batch b2 " +
            "  JOIN t_order_raw r ON r.id = b2.raw_id " +
            "  WHERE r.received_at >= #{start} AND r.received_at < #{end} " +
            "  GROUP BY b2.raw_id " +
            ") latest ON latest.id = b.id " +
            "WHERE b.parse_status = #{parseStatus}")
    long countLatestStatusByRawReceivedAt(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end,
                                          @Param("parseStatus") int parseStatus);

    @Select("SELECT r.source AS source, b.parse_status AS parse_status, COUNT(*) AS raw_count " +
            "FROM t_order_parse_batch b " +
            "JOIN ( " +
            "  SELECT b2.raw_id, MAX(b2.id) AS id " +
            "  FROM t_order_parse_batch b2 " +
            "  JOIN t_order_raw r2 ON r2.id = b2.raw_id " +
            "  WHERE r2.received_at >= #{start} AND r2.received_at < #{end} " +
            "  GROUP BY b2.raw_id " +
            ") latest ON latest.id = b.id " +
            "JOIN t_order_raw r ON r.id = b.raw_id " +
            "GROUP BY r.source, b.parse_status ORDER BY r.source, b.parse_status")
    List<Map<String, Object>> countLatestStatusBySource(@Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM (" +
            "SELECT b.raw_id FROM t_order_parse_batch b " +
            "JOIN t_order_raw r ON r.id = b.raw_id " +
            "WHERE r.received_at >= #{start} AND r.received_at < #{end} " +
            "GROUP BY b.raw_id HAVING COUNT(*) > 1" +
            ") t")
    int countMultiBatchRawByReceivedAt(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);
}

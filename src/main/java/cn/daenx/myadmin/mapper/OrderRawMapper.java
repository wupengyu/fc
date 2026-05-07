package cn.daenx.myadmin.mapper;

import cn.daenx.myadmin.entity.OrderRaw;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderRawMapper extends BaseMapper<OrderRaw> {

    @Select("SELECT * FROM t_order_raw WHERE id = #{rawId} FOR UPDATE")
    OrderRaw selectByIdForUpdate(@Param("rawId") Long rawId);

    @Select("SELECT COUNT(*) " +
            "FROM t_order_raw r " +
            "LEFT JOIN t_order_parse_batch b ON b.raw_id = r.id " +
            "WHERE r.received_at >= #{start} AND r.received_at < #{end} AND b.id IS NULL")
    long countWithoutAnyBatchByReceivedAt(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) " +
            "FROM t_order_raw r " +
            "LEFT JOIN t_order_parse_batch b ON b.raw_id = r.id " +
            "WHERE r.received_at >= #{start} AND r.received_at < #{end} " +
            "AND r.source = #{source} AND b.id IS NULL")
    long countWithoutAnyBatchByReceivedAtAndSource(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end,
                                                   @Param("source") String source);

    @Select("<script>" +
            "SELECT COUNT(*) " +
            "FROM t_order_raw " +
            "WHERE id IN " +
            "<foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "    #{rawId}" +
            "</foreach> " +
            "<if test='messageKeyword != null'> " +
            "AND raw_text LIKE CONCAT('%', #{messageKeyword}, '%') " +
            "</if>" +
            "</script>")
    int countByRawIdsAndKeyword(@Param("rawIds") List<Long> rawIds,
                                @Param("messageKeyword") String messageKeyword);

    @Select("<script>" +
            "SELECT * " +
            "FROM t_order_raw " +
            "WHERE id IN " +
            "<foreach collection='rawIds' item='rawId' open='(' separator=',' close=')'>" +
            "    #{rawId}" +
            "</foreach> " +
            "<if test='messageKeyword != null'> " +
            "AND raw_text LIKE CONCAT('%', #{messageKeyword}, '%') " +
            "</if> " +
            "ORDER BY received_at DESC, id DESC " +
            "LIMIT #{offset}, #{pageSize}" +
            "</script>")
    List<OrderRaw> queryPageByRawIdsAndKeyword(@Param("rawIds") List<Long> rawIds,
                                               @Param("messageKeyword") String messageKeyword,
                                               @Param("offset") int offset,
                                               @Param("pageSize") int pageSize);

    @Select("SELECT IFNULL(source, 'NULL') AS source, COUNT(*) AS raw_count " +
            "FROM t_order_raw WHERE received_at >= #{start} AND received_at < #{end} " +
            "GROUP BY IFNULL(source, 'NULL') ORDER BY source")
    List<Map<String, Object>> countBySource(@Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM (" +
            "SELECT source, from_wxid, sender_wxid, raw_text, received_at, COUNT(*) AS cnt " +
            "FROM t_order_raw " +
            "WHERE received_at >= #{start} AND received_at < #{end} " +
            "GROUP BY source, from_wxid, sender_wxid, raw_text, received_at " +
            "HAVING COUNT(*) > 1" +
            ") d")
    int countExactDuplicateGroups(@Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    @Select("SELECT source, from_wxid, sender_wxid, raw_text, received_at, COUNT(*) AS cnt, " +
            "MIN(id) AS first_raw_id, MAX(id) AS last_raw_id " +
            "FROM t_order_raw " +
            "WHERE received_at >= #{start} AND received_at < #{end} " +
            "GROUP BY source, from_wxid, sender_wxid, raw_text, received_at " +
            "HAVING COUNT(*) > 1 " +
            "ORDER BY cnt DESC, first_raw_id ASC LIMIT #{limit}")
    List<Map<String, Object>> sampleExactDuplicateGroups(@Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end,
                                                         @Param("limit") int limit);
}
